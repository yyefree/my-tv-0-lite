package com.lizongying.mytv0

import com.lizongying.mytv0.requests.HttpClient
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import okhttp3.Request
import org.json.JSONObject

/**
 * 识别当前出口是哪家宽带（移动 / 电信 / 联通）。
 *
 * 为什么需要它：多运营商源里同一个频道挂着好几条地址（移动的 CDN、联通的、电信的）。
 * 跨网地址不是「打不开」，而是「打开了但拉流很慢」——IP 归属错配时的典型表现。
 * 所以先判断自己站在哪一网里，优先用本网地址，本网都不可用才退到跨网。
 */
object IspDetector {
    private const val TAG = "IspDetector"

    enum class Isp { UNKNOWN, MOBILE, TELECOM, UNICOM }

    data class Info(
        val isp: Isp,
        val label: String,   // 运营商原名，如 China Mobile
        val city: String,
        val province: String, // 河北 / 山东…（ip-api 的 regionName，去掉「省/市」后缀）
        val ip: String,
    )

    /** 枚举名和源里的 #ISP: 标注文本一致（MOBILE / TELECOM / UNICOM），可直接比较 */
    fun codeOf(isp: Isp): String = when (isp) {
        Isp.MOBILE -> "MOBILE"
        Isp.TELECOM -> "TELECOM"
        Isp.UNICOM -> "UNICOM"
        Isp.UNKNOWN -> ""
    }

    /**
     * 当前生效的运营商：**手动设置优先**。
     * 自动识别依赖 ip-api（HTTP 请求），网络抖动、代理、双出口都可能识别不出来；
     * 用户自己知道家里是移动还是联通，直接指定比猜准。
     */
    fun wantIsp(): Isp {
        val manual = SP.ispOverride
        if (manual.isNotEmpty()) {
            return when (manual) {
                "MOBILE" -> Isp.MOBILE
                "TELECOM" -> Isp.TELECOM
                "UNICOM" -> Isp.UNICOM
                else -> Isp.UNKNOWN
            }
        }
        return isp()
    }

    /** 当前生效的省份：手动优先，否则用自动识别出来的 */
    fun wantRegion(): String =
        SP.regionOverride.ifEmpty { cached?.province ?: "" }

    @Volatile
    private var cached: Info? = null

    @Volatile
    private var lastCheck = 0L

    // 检测是后台线程跑的，识别出来以后通知界面刷新（设置面板的「线路 xxx」）
    private val _labelLive = MutableLiveData<String>()
    val labelLive: LiveData<String>
        get() = _labelLive

    fun current(): Info? = cached

    fun isp(): Isp = cached?.isp ?: Isp.UNKNOWN

    /** 给界面用的一行字，例如「移动·淄博」；手动指定时带「手动」后缀 */
    fun label(): String {
        val ispName = when (wantIsp()) {
            Isp.MOBILE -> "移动"
            Isp.TELECOM -> "电信"
            Isp.UNICOM -> "联通"
            else -> ""
        }
        if (ispName.isEmpty()) {
            return if (cached == null) "未检测" else "未知"
        }
        val region = wantRegion()
        val manual = SP.ispOverride.isNotEmpty() || SP.regionOverride.isNotEmpty()
        val body = if (region.isNotBlank()) "$ispName·$region" else ispName
        return if (manual) "$body(手动)" else body
    }

    /**
     * 同步检测，调用方自己放到后台线程。
     * 抓不到就返回 UNKNOWN，不影响播放（只是退化成原来的测速优选）。
     */
    fun detect(): Info {
        val info = try {
            val req = Request.Builder()
                .url("http://ip-api.com/json/?lang=zh-CN&fields=status,country,regionName,city,isp,org,as,query")
                .build()
            HttpClient.okHttpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)
                if (json.optString("status") != "success") {
                    Info(Isp.UNKNOWN, "Unknown", "", "", "")
                } else {
                    val all = listOf(
                        json.optString("isp"),
                        json.optString("org"),
                        json.optString("as"),
                    ).joinToString(" ")
                    val kind = when {
                        all.contains("Mobile") || all.contains("移动") -> Isp.MOBILE
                        all.contains("Unicom") || all.contains("联通") || all.contains("CNC") -> Isp.UNICOM
                        all.contains("Telecom") || all.contains("Chinanet") || all.contains("电信") -> Isp.TELECOM
                        else -> Isp.UNKNOWN
                    }
                    Info(
                        kind,
                        json.optString("isp"),
                        json.optString("city"),
                        json.optString("regionName").replace("省", "").replace("市", ""),
                        json.optString("query"),
                    )
                }
            }
        } catch (e: Exception) {
            Logger.i(TAG, "detect fail ${e.javaClass.simpleName}")
            Info(Isp.UNKNOWN, "Unknown", "", "", "")
        }
        cached = info
        lastCheck = System.currentTimeMillis()
        Logger.i(TAG, "出口线路: ${info.label} ${info.province} ${info.city} ${info.ip} → ${info.isp}")
        _labelLive.postValue(label())
        return info
    }

    /** 多久重新识别一次：出口变了（换网、换路由）才需要重测 */
    fun detectIfStale() {
        if (cached == null || System.currentTimeMillis() - lastCheck > 10 * 60 * 1000) {
            detect()
        }
    }
}
