package com.lizongying.mytv0

import com.lizongying.mytv0.requests.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    data class Result(val index: Int, val costMs: Long)

    /** 并发探测全部地址，返回首包最快的那条；全都不通返回 null */
    suspend fun fastest(urls: List<String>): Result? = withContext(Dispatchers.IO) {
        val costs = coroutineScope {
            urls.mapIndexed { index, url ->
                async { index to probe(url) }
            }.awaitAll()
        }
        costs.filter { it.second > 0 }
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
