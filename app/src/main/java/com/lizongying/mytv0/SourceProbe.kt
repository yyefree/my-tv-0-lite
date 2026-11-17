package com.lizongying.mytv0

import com.lizongying.mytv0.requests.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 同一频道往往挂着多条地址（不同 CDN / 端口 / 备用 IP）。
 *
 * 原来的逻辑是「按顺序一条条试」：当前这条挂了要等连接超时 3 s、甚至读超时 12 s
 * 才轮到下一条，用户看到的是十几秒黑屏。这里改成：换台失败的那一刻，
 * 对全部地址并发测一次首包，直接跳到最快能用的那条。
 *
 * 测速结果按频道名缓存，下次切回这个台就不用再测。
 */
object SourceProbe {
    private const val TAG = "SourceProbe"

    private val cache = ConcurrentHashMap<String, Int>()

    // 并发探测的闸门：一次最多 8 条同时打，避免把路由器的 NAT 表和带宽一起挤爆
    private val semaphore = Semaphore(8)

    // 探测要快：连不上 2 s 就给结论，别跟着正片播放的超时（3 s/12 s）
    private val probeClient by lazy {
        HttpClient.builder.build().newBuilder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    fun cachedIndex(key: String): Int? = cache[key]

    fun remember(key: String, index: Int) {
        cache[key] = index
    }

    fun forget(key: String) {
        cache.remove(key)
    }

    /** 手动改了线路/省份之后，之前按旧条件选出来的地址全部作废 */
    fun clear() {
        cache.clear()
    }

    data class Result(val index: Int, val costMs: Long)

    /**
     * 并发探测候选地址，返回首包最快的一条；全都不通返回 null。
     *
     * items 第一个元素是地址，第二个元素是「优先级」（由 PlayerFragment 按运营商/省份算出来）：
     *   0 = 同运营商 + 同省（最优：同一个骨干区域，延迟和丢包都最小）
     *   1 = 同运营商（同网不同省）
     *   2 = 同省不同网
     *   3 = 跨网跨省
     * 先只测 0/1 这两档：跨网地址即使首包很快，实际拉流也常常只有几十 KB/s，
     * 先测本网能省掉一次「能打开但播不动」的误判。本网全不通才回退测全部。
     */
    suspend fun fastest(items: List<Pair<String, Int>>): Result? =
        withContext(Dispatchers.IO) {
            val preferred = items.indices.filter { items[it].second <= 1 }
            if (preferred.isNotEmpty()) {
                probeIndices(items, preferred)?.let { return@withContext it }
                Logger.i(TAG, "本网 ${preferred.size} 条均不通，回退测全部")
            }
            probeIndices(items, items.indices.toList())
        }

    private suspend fun probeIndices(
        items: List<Pair<String, Int>>,
        idxs: List<Int>,
    ): Result? {
        // 一个台最多挂过 30 条地址（CCTV-5 那种）。全放开是 30 个并发连接同时打出去，
        // 老路由器的 NAT 表吃紧，而且互相抢带宽会让首包时间失真、选出来的未必真快。
        // 限到 8 路，总耗时基本不变（瓶颈本来就是超时的那几条），但结果更可信。
        val costs = coroutineScope {
            idxs.map { i ->
                async { semaphore.withPermit { i to probe(items[i].first) } }
            }.awaitAll()
        }
        return costs.filter { it.second > 0 }
            .minByOrNull { it.second }
            ?.let { Result(it.first, it.second) }
    }

    /** 首包耗时（ms），小于等于 0 表示这条不可用 */
    private fun probe(url: String): Long {
        if (url.isBlank()) return -1
        val started = System.currentTimeMillis()
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-256")
                .build()
            probeClient.newCall(request).execute().use { resp ->
                val code = resp.code
                if (code in 200..399) {
                    // 只取首包，读到一小段就断开，不把整个清单拉完（直播清单本身很小，也无妨）
                    resp.peekBody(256L).bytes()
                    System.currentTimeMillis() - started
                } else {
                    Logger.i(TAG, "probe $code $url")
                    -1
                }
            }
        } catch (e: Exception) {
            Logger.i(TAG, "probe fail ${e.javaClass.simpleName} $url")
            -1
        }
    }
}
