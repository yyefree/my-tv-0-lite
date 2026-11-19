import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toFile
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonSyntaxException
import com.lizongying.mytv0.ImageHelper
import com.lizongying.mytv0.Logger
import com.lizongying.mytv0.MyTVApplication
import com.lizongying.mytv0.R
import com.lizongying.mytv0.SP
import com.lizongying.mytv0.Utils.getDateFormat
import com.lizongying.mytv0.Utils.getUrls
import com.lizongying.mytv0.bodyAlias
import com.lizongying.mytv0.codeAlias
import com.lizongying.mytv0.data.EPG
import com.lizongying.mytv0.data.Global.gson
import com.lizongying.mytv0.data.Global.typeEPGMap
import com.lizongying.mytv0.data.Global.typeTvList
import com.lizongying.mytv0.data.Source
import com.lizongying.mytv0.data.SourceType
import com.lizongying.mytv0.data.TV
import com.lizongying.mytv0.models.EPGXmlParser
import com.lizongying.mytv0.models.Sources
import com.lizongying.mytv0.models.TVGroupModel
import com.lizongying.mytv0.models.TVListModel
import com.lizongying.mytv0.models.TVModel
import com.lizongying.mytv0.requests.HttpClient
import com.lizongying.mytv0.showToast
import io.github.lizongying.Gua
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream


class MainViewModel : ViewModel() {
    private var timeFormat = if (SP.displaySeconds) "HH:mm:ss" else "HH:mm"

    private lateinit var appDirectory: File
    var listModel: List<TVModel> = emptyList()
    val groupModel = TVGroupModel()
    private var cacheFile: File? = null
    private var cacheChannels = ""
    private var initialized = false

    private lateinit var cacheEPG: File
    private var epgUrl = SP.epg

    private lateinit var imageHelper: ImageHelper

    val sources = Sources()

    private val _channelsOk = MutableLiveData<Boolean>()
    val channelsOk: LiveData<Boolean>
        get() = _channelsOk

    fun setDisplaySeconds(displaySeconds: Boolean) {
        timeFormat = if (displaySeconds) "HH:mm:ss" else "HH:mm"
        SP.displaySeconds = displaySeconds
    }

    fun getTime(): String {
        return getDateFormat(timeFormat)
    }

    fun updateEPG() {
        viewModelScope.launch {
            var success = false
            if (!epgUrl.isNullOrEmpty()) {
                success = updateEPG(epgUrl!!)
            }
            if (!success && !SP.epg.isNullOrEmpty()) {
                updateEPG(SP.epg!!)
            }
        }
    }

    /**
     * 启动后从云端校验频道源。和手动导入（importFromUrl）刻意走两条路：
     *
     *   1. 先等 45 秒，让首屏和第一路播放稳定下来，不跟启动抢带宽
     *   2. 带 If-None-Match，源没改服务端回 304 就直接结束，不下载也不解析
     *   3. 拉到新内容**只写缓存文件，不重建当前频道列表** —— 正在播的节目一点不受影响，
     *      下次冷启动读缓存时自然生效。源更新不该打断看电视的人。
     */
    fun updateConfig() {
        if (!SP.configAutoLoad) return
        val url = SP.configUrl ?: return
        if (!url.startsWith("http", ignoreCase = true)) return
        viewModelScope.launch {
            delay(CONFIG_PULL_DELAY_MS)
            pullConfigQuietly(url)
        }
    }

    private suspend fun pullConfigQuietly(url: String) {
        // viewModelScope 默认跑在主线程，网络请求必须整块切到 IO，
        // 否则直接 NetworkOnMainThreadException（这条踩过一次）
        val text = withContext(Dispatchers.IO) { fetchFresh(url, SP.configEtag) } ?: return

        if (text.length < 64 || !looksLikeChannels(text)) {
            Logger.w(TAG, "云端源格式不认识，忽略 ${text.length}")
            return
        }

        withContext(Dispatchers.IO) {
            val cur = getCache()
            if (cur == text) {
                Logger.i(TAG, "云端源与本地一致")
                return@withContext
            }
            // 先写临时文件再原子替换，避免写到一半断电留下半截缓存把列表搞坏
            try {
                val tmp = File(appDirectory, "channels.new")
                tmp.writeText(text)
                if (tmp.length() <= 0) return@withContext
                val cf = cacheFile ?: return@withContext
                val old = File(appDirectory, "channels.old")
                if (cf.exists()) cf.renameTo(old)
                if (tmp.renameTo(cf)) {
                    old.delete()
                    Logger.i(TAG, "云端源已缓存，下次启动生效 ${text.length}")
                } else {
                    old.renameTo(cf)
                    Logger.w(TAG, "替换缓存失败，已回滚")
                }
            } catch (e: Exception) {
                Logger.w(TAG, "写缓存失败 ${e.javaClass.simpleName}")
            }
        }
    }

    /** 拉取云端源；返回 null 表示 304 未变化、请求失败或内容为空 */
    private fun fetchFresh(url: String, etag: String): String? {
        for (u in getUrls(url)) {
            try {
                val builder = okhttp3.Request.Builder().url(u)
                if (etag.isNotEmpty()) builder.header("If-None-Match", etag)
                HttpClient.okHttpClient.newCall(builder.build()).execute().use { r ->
                    if (r.code == 304) {
                        Logger.i(TAG, "云端源未变化(304) $u")
                        return null
                    }
                    if (!r.isSuccessful) {
                        Logger.w(TAG, "拉源失败 ${r.code} $u")
                        return@use
                    }
                    val tag = r.header("ETag").orEmpty()
                    if (tag.isNotEmpty()) SP.configEtag = tag
                    return r.body?.string()
                }
            } catch (e: Exception) {
                Logger.w(TAG, "拉源异常 ${e.javaClass.simpleName} $u")
            }
        }
        return null
    }

    /** 轻量格式校验：认 M3U 头、DIYP 的 #genre# 分组，或至少像个多行列表 */
    private fun looksLikeChannels(text: String): Boolean {
        val head = text.take(400)
        return head.contains("#EXTM3U") || text.contains("#genre#") || text.count { it == '\n' } > 8
    }

    private fun getCache(): String {
        return if (cacheFile!!.exists()) {
            cacheFile!!.readText()
        } else {
            ""
        }
    }

    fun init(context: Context) {
        val application = context.applicationContext as MyTVApplication
        imageHelper = application.imageHelper

        groupModel.addTVListModel(TVListModel("我的收藏", 0))
        groupModel.addTVListModel(TVListModel("全部頻道", 1))

        appDirectory = context.filesDir
        cacheFile = File(appDirectory, CACHE_FILE_NAME)
        if (!cacheFile!!.exists()) {
            cacheFile!!.createNewFile()
        }

        cacheChannels = getCache()

        if (cacheChannels.isEmpty()) {
            Logger.i(TAG, "cacheChannels isEmpty")
            cacheChannels =
                context.resources.openRawResource(DEFAULT_CHANNELS_FILE).bufferedReader()
                    .use { it.readText() }
        }

        Logger.i(TAG, "cacheChannels $cacheFile $cacheChannels")

        try {
            str2Channels(cacheChannels)
        } catch (e: Exception) {
            Logger.e(TAG, "init", e)
            cacheFile!!.deleteOnExit()
            R.string.channel_read_error.showToast()
        }

        viewModelScope.launch {
            cacheEPG = File(appDirectory, CACHE_EPG)
            if (!cacheEPG.exists()) {
                cacheEPG.createNewFile()
            } else {
                Logger.i(TAG, "cacheEPG exists")
                if (readEPG(cacheEPG.readText())) {
                    Logger.i(TAG, "cacheEPG success")
                } else {
                    Logger.i(TAG, "cacheEPG failure")
                }
            }
        }

        initialized = true

        _channelsOk.value = true
    }

    suspend fun preloadLogo() {
        if (!this::imageHelper.isInitialized) {
            Logger.w(TAG, "imageHelper is not initialized")
            return
        }

        for (tvModel in listModel) {
            var name = tvModel.tv.name
            if (name.isEmpty()) {
                name = tvModel.tv.title
            }
            // 只下载频道自己带的台标。原实现还会退到 live.fanmingming.cn / raw.githubusercontent.com
            // 两个已经不可用的地址，几十个频道 × 每次启动 = 上百次必然失败的请求，首屏因此很卡。
            val url = tvModel.tv.logo
            if (url.isEmpty() || name.isEmpty()) {
                continue
            }

            imageHelper.preloadImage(
                name,
                getUrls(url),
            )
        }
    }

    suspend fun readEPG(input: InputStream): Boolean = withContext(Dispatchers.IO) {
        try {
            val res = EPGXmlParser().parse(input)

            withContext(Dispatchers.Main) {
                val e1 = mutableMapOf<String, List<EPG>>()
                for (m in listModel) {
                    val name = m.tv.name.ifEmpty { m.tv.title }.lowercase()
                    if (name.isEmpty()) {
                        continue
                    }

                    for ((n, epg) in res) {
                        if (name.contains(n, ignoreCase = true)) {
                            m.setEpg(epg)
                            e1[name] = epg
                            break
                        }
                    }
                }
                cacheEPG.writeText(gson.toJson(e1))
            }
            Logger.i(TAG, "readEPG success")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "readEPG", e)
            false
        }
    }

    private suspend fun readEPG(str: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val res: Map<String, List<EPG>>? = gson.fromJson(str, typeEPGMap)
            if (res == null) {
                cacheEPG.delete()
                Logger.e(TAG, "readEPG 缓存内容无效，已清除待重建")
                return@withContext false
            }

            withContext(Dispatchers.Main) {
                for (m in listModel) {
                    val name = m.tv.name.ifEmpty { m.tv.title }.lowercase()
                    if (name.isEmpty()) {
                        continue
                    }

                    val epg = res[name]
                    if (epg != null) {
                        m.setEpg(epg)
                    }
                }
            }
            Logger.i(TAG, "readEPG success")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "readEPG", e)
            false
        }
    }

    private suspend fun updateEPG(url: String): Boolean {
        val urls = url.split(",").flatMap { u -> getUrls(u) }

        var success = false
        for (a in urls) {
            Logger.i(TAG, "request $a")
            withContext(Dispatchers.IO) {
                try {
                    val request = okhttp3.Request.Builder().url(a).build()
                    val response = HttpClient.okHttpClient.newCall(request).execute()

                    if (response.isSuccessful) {
                        if (readEPG(response.bodyAlias()!!.byteStream())) {
                            Logger.i(TAG, "EPG $a success")
                            success = true
                        }
                    } else {
                        Logger.e(TAG, "EPG $a ${response.codeAlias()}")
                    }
                } catch (e: Exception) {
//                    Logger.e(TAG, "EPG $a error", e)
                    Logger.e(TAG, "EPG $a error")
                }
            }

            if (success) {
                break
            }
        }

        return success
    }

    private suspend fun importFromUrl(url: String, id: String = "") {
        val urls = getUrls(url).map { Pair(it, url) }

        var err = 0
        var shouldBreak = false
        for ((a, b) in urls) {
            Logger.i(TAG, "request $a")
            withContext(Dispatchers.IO) {
                try {
                    val request = okhttp3.Request.Builder().url(a).build()
                    val response = HttpClient.okHttpClient.newCall(request).execute()

                    if (response.isSuccessful) {
                        val str = response.bodyAlias()?.string() ?: ""
                        withContext(Dispatchers.Main) {
                            tryStr2Channels(str, null, b, id)
                        }
                        err = 0
                        shouldBreak = true
                    } else {
                        Logger.e(TAG, "Request status ${response.codeAlias()}")
                        err = R.string.channel_status_error
                    }
                } catch (e: JsonSyntaxException) {
                    e.printStackTrace()
                    Logger.e(TAG, "JSON Parse Error", e)
                    err = R.string.channel_format_error
                    shouldBreak = true
                } catch (e: NullPointerException) {
                    e.printStackTrace()
                    Logger.e(TAG, "Null Pointer Error", e)
                    err = R.string.channel_read_error
                } catch (e: Exception) {
                    e.printStackTrace()
                    Logger.e(TAG, "Request error $e")
                    err = R.string.channel_request_error
                }
            }
            if (shouldBreak) break
        }

        if (err != 0) {
            err.showToast()
        }
    }

    fun reset(context: Context) {
        val str = context.resources.openRawResource(DEFAULT_CHANNELS_FILE).bufferedReader()
            .use { it.readText() }

        try {
            str2Channels(str)
        } catch (e: Exception) {
            e.printStackTrace()
            R.string.channel_read_error.showToast()
        }
    }

    fun importFromUri(uri: Uri, id: String = "") {
        if (uri.scheme == "file") {
            val file = uri.toFile()
            Logger.i(TAG, "file $file")
            val str = if (file.exists()) {
                file.readText()
            } else {
                R.string.file_not_exist.showToast()
                return
            }

            tryStr2Channels(str, file, uri.toString(), id)
        } else {
            viewModelScope.launch {
                importFromUrl(uri.toString(), id)
            }
        }
    }

    fun tryStr2Channels(str: String, file: File?, url: String, id: String = "") {
        try {
            if (str2Channels(str)) {
                Logger.i(TAG, "write to cacheFile $cacheFile $str")
                cacheFile!!.writeText(str)
                Logger.i(TAG, "cacheFile ${getCache()}")
                cacheChannels = str
                if (url.isNotEmpty()) {
                    SP.configUrl = url
                    val source = Source(
                        id = id,
                        uri = url
                    )
                    sources.addSource(
                        source
                    )
                }
                _channelsOk.value = true
                R.string.channel_import_success.showToast()
                Logger.i(TAG, "channel import success")
            } else {
                R.string.channel_import_error.showToast()
                Logger.w(TAG, "channel import error")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "tryStr2Channels", e)
            file?.deleteOnExit()
            R.string.channel_read_error.showToast()
        }
    }

    private fun str2Channels(str: String): Boolean {
        var string = str
        if (initialized && string == cacheChannels) {
            Logger.w(TAG, "same channels")
            return true
        }

        val g = Gua()
        if (g.verify(str)) {
            string = g.decode(str)
            // 内置资源若是编码过的，解码后把明文写回缓存：
            // 下次设置页、网页端看到的就是可读的 TXT，改源直接编辑缓存即可。
            try {
                cacheFile?.writeText(string)
            } catch (e: Exception) {
                Logger.w(TAG, "写回明文缓存失败 ${e.javaClass.simpleName}")
            }
        }

        if (string.isEmpty()) {
            Logger.w(TAG, "channels is empty")
            return false
        }

        if (initialized && string == cacheChannels) {
            Logger.w(TAG, "same channels")
            return true
        }

        val list: List<TV>

        when (string[0]) {
            '[' -> {
                try {
                    list = gson.fromJson(string, typeTvList)
                    Logger.i(TAG, "导入频道 ${list.size} $list")
                } catch (e: Exception) {
                    Logger.e(TAG, "str2Channels", e)
                    return false
                }
            }

            '#' -> {
                val lines = string.lines()
                val nameRegex = Regex("""tvg-name="([^"]+)"""")
                val logRegex = Regex("""tvg-logo="([^"]+)"""")
                val numRegex = Regex("""tvg-chno="([^"]+)"""")
                val epgRegex = Regex("""x-tvg-url="([^"]+)"""")
                val groupRegex = Regex("""group-title="([^"]+)"""")

                val l = mutableListOf<TV>()
                val tvMap = mutableMapOf<String, List<TV>>()

                var tv = TV()
                var pendingIsp = ""
                var pendingRegion = ""
                for (line in lines) {
                    val trimmedLine = line.trim()
                    if (trimmedLine.isEmpty()) {
                        continue
                    }
                    if (trimmedLine.startsWith("#EXTM3U")) {
                        epgUrl = epgRegex.find(trimmedLine)?.groupValues?.get(1)?.trim()
                    } else if (trimmedLine.startsWith("#EXTINF")) {
                        val key = tv.group + tv.name
                        if (key.isNotEmpty()) {
                            tvMap[key] =
                                if (!tvMap.containsKey(key)) listOf(tv) else tvMap[key]!! + tv
                        }
                        tv = TV()
                        pendingIsp = ""
                        pendingRegion = ""
                        val info = trimmedLine.split(",")
                        tv.title = info.last().trim()
                        var name = nameRegex.find(info.first())?.groupValues?.get(1)?.trim()
                        tv.name = if (name.isNullOrEmpty()) tv.title else name
                        tv.logo = logRegex.find(info.first())?.groupValues?.get(1)?.trim() ?: ""
                        // 上游用 ?.toInt() ?: -1：toInt() 抛的是异常不是 null，?: 兜不住。
                        // 用户导入的源里 tvg-chno 只要有一个非纯数字，整个解析就炸在这里。
                        tv.number =
                            numRegex.find(info.first())?.groupValues?.get(1)?.trim()?.toIntOrNull()
                                ?: -1
                        tv.group = groupRegex.find(info.first())?.groupValues?.get(1)?.trim() ?: ""
                    } else if (trimmedLine.startsWith("#EXTVLCOPT:http-")) {
                        val keyValue =
                            trimmedLine.substringAfter("#EXTVLCOPT:http-").split("=", limit = 2)
                        if (keyValue.size == 2) {
                            tv.headers = if (tv.headers == null) {
                                mapOf<String, String>(keyValue[0] to keyValue[1])
                            } else {
                                tv.headers!!.toMutableMap().apply {
                                    this[keyValue[0]] = keyValue[1]
                                }
                            }
                        }
                    } else if (trimmedLine.startsWith("#ISP:")) {
                        // 多运营商源用它标注下一条地址属于哪家：MOBILE / TELECOM / UNICOM
                        pendingIsp = trimmedLine.substringAfter("#ISP:").trim().uppercase()
                    } else if (trimmedLine.startsWith("#REGION:")) {
                        // 标注下一条地址在哪个省：同省地址少跳骨干，测速时优先
                        pendingRegion = trimmedLine.substringAfter("#REGION:").trim()
                    } else if (!trimmedLine.startsWith("#")) {
                        tv.uris = if (tv.uris.isEmpty()) {
                            listOf(trimmedLine)
                        } else {
                            tv.uris.toMutableList().apply {
                                this.add(trimmedLine)
                            }
                        }
                        // uriIsp 与 uris 一一对应，保证后面按运营商挑地址时下标不错位
                        tv.uriIsp = tv.uriIsp + pendingIsp
                        tv.uriRegion = tv.uriRegion + pendingRegion
                        // 标注只对下一条地址生效。原来不清空，缺 #ISP 的那条会静默沿用上一条的供应商，
                        // 自带的源是 1:1 标好的没问题，用户导入的源就会被误标成同网地址。
                        pendingIsp = ""
                        pendingRegion = ""
                    }
                }
                val key = tv.group + tv.name
                if (key.isNotEmpty()) {
                    tvMap[key] = if (!tvMap.containsKey(key)) listOf(tv) else tvMap[key]!! + tv
                }
                for ((_, tv) in tvMap) {
                    val uris = tv.map { t -> t.uris }.flatten()
                    val uriIsp = tv.map { t -> t.uriIsp }.flatten()
                    val uriRegion = tv.map { t -> t.uriRegion }.flatten()
                    val t0 = tv[0]
                    val t1 = TV(
                        -1,
                        t0.name,
                        t0.title,
                        "",
                        t0.logo,
                        "",
                        uris,
                        0,
                        t0.headers,
                        t0.group,
                        SourceType.UNKNOWN,
                        t0.number,
                        emptyList(),
                        uriIsp,
                        uriRegion,
                    )
                    l.add(t1)
                }
                list = l
                Logger.i(TAG, "导入频道 ${list.size} $list")
            }

            else -> {
                val lines = string.lines()
                var group = ""
                val l = mutableListOf<TV>()
                val tvMap = mutableMapOf<String, List<String>>()
                for (line in lines) {
                    val trimmedLine = line.trim()
                    if (trimmedLine.isNotEmpty()) {
                        if (trimmedLine.contains("#genre#")) {
                            group = trimmedLine.split(',', limit = 2)[0].trim()
                        } else {
                            if (!trimmedLine.contains(",")) {
                                continue
                            }
                            val arr = trimmedLine.split(',').map { it.trim() }
                            val title = arr.first().trim()
                            val uris = arr.drop(1)

                            val key = group + title
                            if (!tvMap.containsKey(key)) {
                                tvMap[key] = listOf(group)
                            }
                            tvMap[key] = tvMap[key]!! + uris
                        }
                    }
                }
                for ((title, uris) in tvMap) {
                    val channelGroup = uris.first();
                    uris.drop(1);
                    val tv = TV(
                        -1,
                        "",
                        title.removePrefix(channelGroup),
                        "",
                        "",
                        "",
                        uris,
                        0,
                        emptyMap(),
                        channelGroup,
                        SourceType.UNKNOWN,
                        -1,
                        emptyList(),
                    )

                    l.add(tv)
                }
                list = l
                Logger.d(TAG, "导入频道 $list")
                Logger.i(TAG, "导入频道 ${list.size}")
            }
        }

        groupModel.initTVGroup()

        val map: MutableMap<String, MutableList<TVModel>> = mutableMapOf()
        for (v in list) {
            if (v.group !in map) {
                map[v.group] = mutableListOf()
            }
            map[v.group]?.add(TVModel(v))
        }

        val listModelNew: MutableList<TVModel> = mutableListOf()
        var groupIndex = 2
        var id = 0
        for ((k, v) in map) {
            val listTVModel = TVListModel(k.ifEmpty { "未知" }, groupIndex)
            for ((listIndex, v1) in v.withIndex()) {
                v1.tv.id = id
                v1.setLike(SP.getLike(id))
                v1.setGroupIndex(groupIndex)
                v1.listIndex = listIndex
                listTVModel.addTVModel(v1)
                listModelNew.add(v1)
                id++
            }
            groupModel.addTVListModel(listTVModel)
            groupIndex++
        }

        listModel = listModelNew

        // 全部频道
        groupModel.tvGroupValue[1].setTVListModel(listModel)

        if (string != cacheChannels && g.encode(string) != cacheChannels) {
            groupModel.initPosition()
        }

        groupModel.setChange()

        viewModelScope.launch {
            preloadLogo()
        }

        return true
    }

    companion object {
        private const val TAG = "MainViewModel"
        const val CACHE_FILE_NAME = "channels.txt"
        const val CACHE_EPG = "epg.xml"
        val DEFAULT_CHANNELS_FILE = R.raw.channels

        // 启动后多久去云端校验源。留足余量，避免和首屏、第一路播放抢带宽
        private const val CONFIG_PULL_DELAY_MS = 45_000L
    }
}