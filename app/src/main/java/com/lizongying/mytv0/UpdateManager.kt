package com.lizongying.mytv0

import android.app.DownloadManager
import android.app.DownloadManager.Request
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.MutableLiveData
import com.lizongying.mytv0.Utils.getUrls
import com.lizongying.mytv0.data.Global.gson
import com.lizongying.mytv0.data.ReleaseResponse
import com.lizongying.mytv0.requests.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONObject


class UpdateManager(
    private var context: Context,
    private var versionCode: Long
) :
    ConfirmationFragment.ConfirmationListener {

    private var downloadReceiver: DownloadReceiver? = null
    var release: ReleaseResponse? = null

    // 后台自动升级：每小时静默探测一次，通了就静默下载，下完只在确认安装时打扰用户
    private var pendingInstallMode = false

    // 最近一次探测失败的通俗原因，给「更新应用」失败提示用
    private var lastError = ""

    /** 把异常翻译成用户看得懂的原因，而不是只丢一句「版本获取失败」 */
    private fun describeError(e: Exception): String {
        val msg = e.message ?: ""
        return when {
            e is javax.net.ssl.SSLException || msg.contains("reset", true) ->
                "连接被重置（${e.javaClass.simpleName}）"

            e is java.net.SocketTimeoutException -> "连接超时"
            e is java.net.UnknownHostException -> "域名解析失败"
            else -> e.javaClass.simpleName
        }
    }

    private suspend fun getRelease(): ReleaseResponse? {
        val urls = VERSION_URLS.flatMap { getUrls(it) }

        for (u in urls) {
            Logger.i(TAG, "request $u")
            // 注意：以前这里直接 withContext { return@withContext ... }，返回值被丢弃，函数最后必然 return null，
            // 导致「更新应用」永远提示版本获取失败。必须把 withContext 的结果接出来再返回。
            val result: ReleaseResponse? = withContext(Dispatchers.IO) {
                try {
                    val request = okhttp3.Request.Builder().url(u).build()
                    val response = HttpClient.okHttpClient.newCall(request).execute()

                    if (response.isSuccessful) {
                        val body = response.bodyAlias()
                        if (body == null) {
                            Logger.e(TAG, "getRelease $u body null")
                            null
                        } else {
                            val text = body.string()
                            Logger.i(TAG, "getRelease body ${text.length} $text")
                            // api.github.com/contents 返回的是 {"content":"<base64>","encoding":"base64"}，
                            // 必须解出来才是真正的 version.json，否则解析必然拿到 null 字段。
                            parseRelease(unwrapApiContent(text))
                        }
                    } else {
                        Logger.e(TAG, "getRelease $u ${response.codeAlias()}")
                        null
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "getRelease $u error $e")
                    lastError = describeError(e)
                    null
                }
            }

            if (result != null) return result
        }

        return null
    }

    // 把 GitHub Contents API 的 base64 包装层拆掉；不是这种格式就原样返回
    private fun unwrapApiContent(text: String): String {
        return try {
            if (!text.contains("\"encoding\"") || !text.contains("base64")) return text
            val obj = JSONObject(text)
            val b64 = obj.optString("content", "")
            if (b64.isEmpty()) return text
            String(android.util.Base64.decode(b64.replace("\n", ""), android.util.Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            Logger.e(TAG, "unwrapApiContent error $e")
            text
        }
    }

    // 用 JSONObject 直接解析：不依赖反射/gson，避免 R8 混淆后字段名对不上导致永远拿不到版本号
    private fun parseRelease(text: String): ReleaseResponse? {
        return try {
            val obj = JSONObject(text)
            ReleaseResponse(
                if (obj.isNull("version_code")) null else obj.getInt("version_code"),
                if (obj.isNull("version_name")) null else obj.getString("version_name"),
                if (obj.isNull("apk_name")) null else obj.getString("apk_name"),
                if (obj.isNull("apk_url")) null else obj.getString("apk_url")
            )
        } catch (e: Exception) {
            Logger.e(TAG, "parseRelease error $e")
            null
        }
    }

    // ==================== 后台自动升级 ====================
    // 思路：每小时静默探测一次 GitHub 可达性；能拿到版本就后台静默下载 APK；
    // 下载完成后才提示用户"可以升级"，由用户确认后安装。

    fun startAutoPoll() {
        if (pollJob?.isActive == true) {
            Logger.i(TAG, "startAutoPoll 已在运行，跳过")
            return
        }
        Logger.i(
            TAG,
            "startAutoPoll 启动：首轮 ${INITIAL_DELAY_MS / 1000}s 后，之后每 ${POLL_INTERVAL_MS / 60000} 分钟一次"
        )
        pollJob = CoroutineScope(Dispatchers.Main).launch {
            delay(INITIAL_DELAY_MS)
            while (isActive) {
                try {
                    autoCheck()
                } catch (e: Exception) {
                    Logger.e(TAG, "autoCheck error", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        // 上次进程被杀时可能已经下完，启动后补一次安装提示
        CoroutineScope(Dispatchers.Main).launch {
            delay(3000)
            if (hasPendingApk()) promptPendingInstall()
        }
    }

    fun stopAutoPoll() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun autoCheck() {
        SP.updateLastCheck = System.currentTimeMillis()
        Logger.i(TAG, "autoCheck 开始静默探测")
        setStatus("检测中…")

        if (hasPendingApk()) {
            promptPendingInstall()
            return
        }

        val downloadId = SP.updateDownloadId
        if (downloadId > 0) {
            when (queryDownloadStatus(downloadId)) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    // 下载记录是成功但文件可能已被删除（换机、手动清理），必须核实文件还在
                    val file = File(
                        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                        SP.updatePendingApk
                    )
                    if (file.exists() && file.length() > 0) {
                        SP.updatePendingReady = true
                        Logger.i(TAG, "autoCheck 上一轮后台下载已完成 (${file.length()} B)")
                        setStatus("已下载完成，等确认安装")
                        promptPendingInstall()
                        return
                    }
                    Logger.i(TAG, "autoCheck 下载记录成功但文件已不存在，清理后重新探测")
                    clearPendingDownload()
                }

                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> {
                    Logger.i(TAG, "autoCheck 下载仍在进行，本轮跳过")
                    trackProgress(downloadId)
                    return
                }

                else -> {
                    Logger.i(TAG, "autoCheck 上一轮下载未成功，清理后重试")
                    clearPendingDownload()
                }
            }
        }

        val r = getRelease()
        if (r == null) {
            Logger.i(TAG, "autoCheck 探测失败：当前网络拿不到版本信息，等下一轮")
            setStatus("未连通，等下一轮重试")
            return
        }
        val remoteCode = r.version_code ?: return
        Logger.i(TAG, "autoCheck 远端 ${r.version_name}($remoteCode) 本地 $versionCode")
        if (remoteCode <= versionCode) {
            Logger.i(TAG, "autoCheck 已是最新版本")
            setStatus("已是最新版本")
            return
        }
        if (remoteCode.toLong() == SP.updateDismissedCode) {
            Logger.i(TAG, "autoCheck 该版本已被用户忽略，不再提示")
            setStatus("发现 ${r.version_name}，已被忽略")
            return
        }
        Logger.i(TAG, "autoCheck 发现新版本，开始后台静默下载 ${r.apk_name}")
        startDownload(r)
    }

    private fun hasPendingApk(): Boolean {
        if (!SP.updatePendingReady) return false
        val file = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            SP.updatePendingApk
        )
        val exists = file.exists() && file.length() > 0
        Logger.i(TAG, "hasPendingApk ${SP.updatePendingApk} -> $exists (${file.length()} B)")
        if (!exists) SP.updatePendingReady = false
        return exists
    }

    private fun queryDownloadStatus(id: Long): Int {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var status = -1
        try {
            downloadManager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (index >= 0) status = cursor.getInt(index)
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "queryDownloadStatus error", e)
        }
        return status
    }

    private fun clearPendingDownload() {
        SP.updateDownloadId = -1L
        SP.updatePendingReady = false
    }

    // ==================== 状态可见性 ====================
    // 后台静默探测/下载时界面本来什么都不显示，用户只会觉得「点了没反应」。
    // 把当前状态写进静态 LiveData，设置面板里那行小字实时变化，让功能「看得见」。

    private fun setStatus(text: String) {
        status.postValue(text)
    }

    /** 每 2 秒查一次下载进度，直到成功/失败；期间把百分比写进状态行 */
    private fun trackProgress(id: Long) {
        progressJob?.cancel()
        progressJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                delay(2000)
                when (val percent = queryDownloadProgress(id)) {
                    null -> {
                        setStatus("下载记录已失效")
                        break
                    }

                    -1 -> {
                        setStatus("下载失败，下一轮重试")
                        break
                    }

                    100 -> {
                        setStatus("已下载完成，等确认安装")
                        break
                    }

                    else -> setStatus("正在后台下载 $percent%")
                }
            }
        }
    }

    /** 0-100 进度；-1 下载失败；null 查不到记录 */
    private fun queryDownloadProgress(id: Long): Int? {
        val downloadManager =
            context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        try {
            downloadManager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return null
                }
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (statusIndex < 0) {
                    return null
                }
                when (cursor.getInt(statusIndex)) {
                    DownloadManager.STATUS_SUCCESSFUL -> return 100
                    DownloadManager.STATUS_FAILED -> return -1
                }

                val doneIndex =
                    cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                if (doneIndex < 0 || totalIndex < 0) {
                    return null
                }
                val done = cursor.getLong(doneIndex)
                val total = cursor.getLong(totalIndex)
                if (total <= 0) {
                    return 0
                }
                return ((done * 100) / total).toInt().coerceIn(0, 99)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "queryDownloadProgress error", e)
        }
        return null
    }

    private fun promptPendingInstall() {
        val activity = context as? FragmentActivity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        if (activity.supportFragmentManager.findFragmentByTag(TAG) is ConfirmationFragment) return
        Logger.i(TAG, "promptPendingInstall 提示安装 ${SP.updatePendingName}")
        pendingInstallMode = true
        ConfirmationFragment(this@UpdateManager, "新版本 ${SP.updatePendingName} 已下载完成", true)
            .show(activity.supportFragmentManager, TAG)
    }

    private fun installPending() {
        val apkFile = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            SP.updatePendingApk
        )
        Logger.i(TAG, "installPending apkFile $apkFile (${apkFile.length()} B)")
        if (!apkFile.exists()) {
            Logger.e(TAG, "installPending APK 不存在")
            SP.updatePendingReady = false
            return
        }
        try {
            val apkUri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.FileProvider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            SP.updatePendingReady = false
            SP.updateDownloadId = -1L
        } catch (e: Exception) {
            Logger.e(TAG, "installPending error", e)
        }
    }

    fun checkAndUpdate() {
        Logger.i(TAG, "checkAndUpdate")
        setStatus("检查中…")
        CoroutineScope(Dispatchers.Main).launch {
            var text = "版本获取失败"
            var update = false
            try {
                release = getRelease()
                Logger.i(TAG, "versionCode $versionCode ${release?.version_code} ${release?.version_name}")
                if (release?.version_code != null) {
                    if (release?.version_code!! > versionCode) {
                        text = "最新版本：${release?.version_name}"
                        update = true
                        setStatus("发现 ${release?.version_name}")
                    } else {
                        text = "已是最新版本，不需要更新"
                        setStatus("已是最新版本")
                    }
                } else {
                    // 说清楚为什么失败：是域名解析、连接被重置还是超时，
                    // 再告诉用户后台每小时会自动重试，避免「点了没反应」的观感。
                    text = "版本获取失败：" + (lastError.ifEmpty { "网络不可达" })
                    setStatus("未连通，等自动重试")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Error occurred: ${e.message}", e)
                text = "版本获取失败：" + describeError(e)
                setStatus("未连通，等自动重试")
            }
            updateUI(text, update)
        }
    }

    private fun updateUI(text: String, update: Boolean) {
        val dialog = ConfirmationFragment(this@UpdateManager, text, update)
        dialog.show((context as FragmentActivity).supportFragmentManager, TAG)
    }

    // 生成 APK 下载通道：jsDelivr 代理仓库 dist/ 目录优先（实测能拉全包），
    // github.com 官方直链兜底（只有域名没被污染时才通，但来源最可信所以留着）。
    private fun buildApkUrls(release: ReleaseResponse): List<String> {
        val name = release.apk_name ?: return emptyList()
        val list = mutableListOf<String>()
        for (cdn in APK_CDNS) list.add("$cdn/$name")
        release.apk_url?.takeIf { it.isNotEmpty() }?.let { list.add(it) }
        Logger.i(TAG, "buildApkUrls ${list.size} 条: $list")
        return list
    }

    // 下载前把「期望的版本号」记下来，下完要核对，避免拿到 jsDelivr 缓存的旧包
    private var targetVersionCode = 0L
    private var apkUrlList: List<String> = emptyList()
    private var apkUrlIndex = 0

    private fun startDownload(release: ReleaseResponse) {
        if (release.apk_name.isNullOrEmpty() || release.apk_url.isNullOrEmpty()) {
            return
        }

        // 先把要装的版本记进 SP：即使进程中途被杀，下次启动也能靠 DownloadManager 查询恢复
        SP.updatePendingCode = release.version_code?.toLong() ?: 0L
        SP.updatePendingName = release.version_name ?: ""
        SP.updatePendingApk = release.apk_name
        SP.updatePendingReady = false
        targetVersionCode = release.version_code?.toLong() ?: 0L

        apkUrlList = buildApkUrls(release)
        apkUrlIndex = 0
        enqueueCurrent()
    }

    private fun enqueueCurrent() {
        if (apkUrlIndex >= apkUrlList.size) {
            Logger.e(TAG, "所有下载通道都不通")
            setStatus("下载通道全部不通，等下一轮")
            clearPendingDownload()
            return
        }
        val url = apkUrlList[apkUrlIndex]
        val apkName = SP.updatePendingApk
        Logger.i(TAG, "enqueueCurrent ${apkUrlIndex + 1}/${apkUrlList.size} $url")

        val downloadManager =
            context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.mkdirs()
        // 目的文件已存在时 DownloadManager 会直接失败，所以先把残片删掉
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), apkName).delete()

        val request = Request(Uri.parse(url))
        request.setDestinationInExternalFilesDir(
            context,
            Environment.DIRECTORY_DOWNLOADS,
            apkName
        )
        request.setTitle("${context.getString(R.string.app_name)} ${SP.updatePendingName}")
        request.setNotificationVisibility(Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setAllowedOverRoaming(false)
        request.setMimeType("application/vnd.android.package-archive")

        val downloadReference = downloadManager.enqueue(request)
        SP.updateDownloadId = downloadReference
        Logger.i(TAG, "startDownload enqueue id=$downloadReference $apkName")

        // 旧 receiver 要先注销，否则一次失败会触发好几条通道同时排队
        unregisterReceiverQuietly()
        downloadReceiver = DownloadReceiver(apkName, downloadReference)

        val intentFilter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(downloadReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(downloadReceiver, intentFilter)
        }

        trackProgress(downloadReference)
        setStatus("正在后台下载 0%")
    }

    private fun unregisterReceiverQuietly() {
        val r = downloadReceiver ?: return
        try {
            context.unregisterReceiver(r)
        } catch (e: Exception) {
            Logger.i(TAG, "unregisterReceiver 已失效 ${e.javaClass.simpleName}")
        }
        downloadReceiver = null
    }

    /**
     * 下载完的包必须验两件事，缺一不可：
     *  1) 签名与当前应用一致 —— 防止走镜像时被人换包投毒（这是走第三方的底线）
     *  2) versionCode 等于 version.json 里的值 —— 防止 jsDelivr 的 12 小时缓存把旧包发过来
     */
    private fun verifyApk(file: File): Boolean {
        return try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val pkg = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
                ?: run {
                    Logger.e(TAG, "verifyApk 读不出包信息")
                    return false
                }
            @Suppress("DEPRECATION")
            val newSigs = pkg.signatures
            @Suppress("DEPRECATION")
            val mySigs = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
            val sigOk = newSigs != null && mySigs != null &&
                newSigs.any { n -> mySigs.any { it.toCharsString() == n.toCharsString() } }
            val vc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkg.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkg.versionCode.toLong()
            }
            val verOk = targetVersionCode <= 0L || vc == targetVersionCode
            Logger.i(TAG, "verifyApk 签名一致=$sigOk 版本=$vc(期望 $targetVersionCode) 版本一致=$verOk")
            sigOk && verOk
        } catch (e: Exception) {
            Logger.e(TAG, "verifyApk error", e)
            false
        }
    }

    // 当前通道下完但校验没过（或被删），换下一条继续试
    private fun tryNextChannel(file: File?, reason: String) {
        Logger.i(TAG, "通道 ${apkUrlIndex + 1} 不可用（$reason）")
        file?.let { if (it.exists()) it.delete() }
        apkUrlIndex++
        if (apkUrlIndex < apkUrlList.size) {
            setStatus("换通道重试（${apkUrlIndex + 1}/${apkUrlList.size}）")
            enqueueCurrent()
        } else {
            setStatus("下载通道全部失败，等下一轮")
            clearPendingDownload()
        }
    }

    private fun getDownloadProgress(
        context: Context,
        downloadId: Long,
        progressListener: (Int) -> Unit
    ) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val handler = Handler(Looper.getMainLooper())
        val intervalMillis: Long = 1000

        handler.post(object : Runnable {
            override fun run() {
                Logger.i(TAG, "search")
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor: Cursor = downloadManager.query(query)
                cursor.use {
                    if (it.moveToFirst()) {
                        val bytesDownloadedIndex =
                            it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val bytesTotalIndex =
                            it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                        // 检查列名是否存在
                        if (bytesDownloadedIndex != -1 && bytesTotalIndex != -1) {
                            val bytesDownloaded = it.getInt(bytesDownloadedIndex)
                            val bytesTotal = it.getInt(bytesTotalIndex)

                            if (bytesTotal != -1) {
                                val progress = (bytesDownloaded * 100L / bytesTotal).toInt()
                                progressListener(progress)
                                if (progress == 100) {
                                    return
                                }
                            }
                        }
                    }
                }

//                handler.postDelayed(this, intervalMillis)
            }
        })
    }

    private inner class DownloadReceiver(
        private val apkFileName: String,
        private val downloadReference: Long
    ) : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val reference = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            Logger.i(TAG, "DownloadReceiver id=$reference")

            if (reference == downloadReference) {
                val downloadManager =
                    context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(downloadReference)
                val cursor = downloadManager.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (statusIndex < 0) {
                        Logger.i(TAG, "Download failure")
                        return
                    }
                    val status = cursor.getInt(statusIndex)

                    val progressIndex =
                        cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    if (progressIndex < 0) {
                        Logger.i(TAG, "Download failure")
                        return
                    }
                    val progress = cursor.getInt(progressIndex)

                    val totalSizeIndex =
                        cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val totalSize = cursor.getInt(totalSizeIndex)

                    cursor.close()

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            val file = File(
                                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                                apkFileName
                            )
                            Logger.i(TAG, "下载完成 $apkFileName (${file.length()} B)，开始校验")
                            when {
                                !file.exists() || file.length() <= 0L ->
                                    tryNextChannel(file, "文件为空")

                                !verifyApk(file) -> tryNextChannel(file, "签名或版本号对不上")

                                else -> {
                                    SP.updatePendingReady = true
                                    setStatus("已下载完成，等确认安装")
                                    Logger.i(TAG, "校验通过，等待用户确认安装")
                                    promptPendingInstall()
                                }
                            }
                        }

                        DownloadManager.STATUS_FAILED -> {
                            tryNextChannel(
                                File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), apkFileName),
                                "下载失败"
                            )
                        }

                        else -> {
                            // Update UI with download progress
                            val percentage = progress * 100 / totalSize
                            Logger.i(TAG, "Download progress: $percentage%")
                        }
                    }
                }
            }
        }

    }

    companion object {
        private const val TAG = "UpdateManager"
        private const val BUFFER_SIZE = 8192

        // 版本探测端点。2026-11-18 实测（移动宽带、github.com 域名被 DNS 污染的环境）：
        //   raw.githubusercontent.com  FAIL（SSL）
        //   cdn.jsdelivr.net          FAIL（SSL）      ← 原来用的就是这两条，所以升级永远失败
        //   gcore.jsdelivr.net        OK 0.9s
        //   fastly.jsdelivr.net       OK 1.4s
        //   testingcf.jsdelivr.net    OK 2.5s
        //   api.github.com            OK 0.6s（独立域名，污染不同步）
        // 所以按「实测能通且快」的顺序排，坏的放最后当兜底。
        private val VERSION_URLS = listOf(
            "https://gcore.jsdelivr.net/gh/yyefree/my-tv-0-lite@main/version.json",
            "https://fastly.jsdelivr.net/gh/yyefree/my-tv-0-lite@main/version.json",
            "https://testingcf.jsdelivr.net/gh/yyefree/my-tv-0-lite@main/version.json",
            "https://api.github.com/repos/yyefree/my-tv-0-lite/contents/version.json",
            "https://raw.githubusercontent.com/yyefree/my-tv-0-lite/main/version.json",
            "https://cdn.jsdelivr.net/gh/yyefree/my-tv-0-lite@main/version.json"
        )

        // APK 下载通道。github.com 直链必然被污染，所以主力是 jsDelivr 代理仓库里的 dist/ 包。
        // 实测 gcore 能整包拉（3689054 B，Range 返回 206，支持断点续传）。
        private val APK_CDNS = listOf(
            "https://gcore.jsdelivr.net/gh/yyefree/my-tv-0-lite@main/dist",
            "https://fastly.jsdelivr.net/gh/yyefree/my-tv-0-lite@main/dist",
            "https://testingcf.jsdelivr.net/gh/yyefree/my-tv-0-lite@main/dist"
        )

        // 每 1 小时静默探测一次；App 启动后先等 60 秒，避免和首屏播放抢带宽
        private const val POLL_INTERVAL_MS = 60L * 60 * 1000
        private const val INITIAL_DELAY_MS = 60L * 1000

        // 轮询任务放静态，Activity 重建时不会重复起一份
        private var pollJob: Job? = null

        // 设置面板「更新应用」下面那行状态字：让用户看到后台在探测/下载，而不是一片沉默
        val status = MutableLiveData<String>("")

        // 下载进度轮询（每 2 秒一次，完成后自动结束）
        private var progressJob: Job? = null
    }

    override fun onConfirm() {
        if (pendingInstallMode) {
            pendingInstallMode = false
            installPending()
            return
        }
        Logger.i(TAG, "onConfirm $release")
        release?.let { startDownload(it) }
    }

    override fun onCancel() {
        if (pendingInstallMode) {
            pendingInstallMode = false
            // 用户选了「稍后」，本轮不再自动弹这个版本；文件留着，手动点「更新应用」仍可装
            SP.updateDismissedCode = SP.updatePendingCode
            Logger.i(TAG, "用户忽略本次安装提示：${SP.updatePendingName}")
        }
    }

    fun destroy() {
        if (downloadReceiver != null) {
            context.unregisterReceiver(downloadReceiver)
            Logger.i(TAG, "destroy downloadReceiver")
        }
    }
}