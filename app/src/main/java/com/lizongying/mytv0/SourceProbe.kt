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
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 同一频道往往挂着多条地址（不同 CDN / 端口 / 备用 IP）。
 *
 * 原来的逻辑是「按顺序一条条试」：当前这条挂了要等连接超时 3 s、甚至读超时 12 s
 * 才轮到下一条，用户看到的是十几秒黑屏。这里改成：换台失败的那一刻，
 * 对全部地址并发测一遍，直接跳到最能播的那条。
 *
 * 判定从「首包能不能到」改成「能不能持续吐出字节」：实测首包通 59.8%、真能拉流只有 16.5%，
 * 按首包选源会大量选中「能连上、播不动」的地址。现在每条地址先解出 m3u8 清单里的
 * 真实分片，再拉一小段数据算吞吐，按吞吐量排序。
 *
 * 测速结果按频道名缓存，下次切回这个台就不用再测。
 */
object SourceProbe {
    private const val TAG = "SourceProbe"

    // 单条地址的测量窗口：1.2 s。再长就为了区分 5 MB/s 和 6 MB/s 而牺牲起播时间了
    private const val MEASURE_WINDOW_MS = 1200L

    // 拉够这么多就提前收工，不必等满窗口
    private const val MEASURE_TARGET_BYTES = 192L * 1024

    // 1.2 s 内连 64 KB 都拉不到（<53 KB/s，约 430 kbps）就没法播任何一路直播
    private const val MIN_BYTES = 64L * 1024

    private const val READ_BUF = 24 * 1024

    private val cache = ConcurrentHashMap<String, Int>()

    // 并发探测的闸门：一次最多 8 条同时打，避免把路由器的 NAT 表和带宽一起挤爆，
    // 也避免并发互相抢带宽让测出来的速度不准
    private val semaphore = Semaphore(8)

    // 探测客户端：连不上 2 s 给结论；连着但 2 s 吐不出数据算卡死
    private val probeClient by lazy {
        HttpClient.builder.build().newBuilder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
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

    /** costMs 是测速耗时，speedKb 是拉流速度（KB/s），越大越能播 */
    data class Result(val index: Int, val costMs: Long, val speedKb: Long)

    /** 单条地址的测量结果；speedKb 为 0 表示没拉出数据 */
    private data class Score(val elapsedMs: Long, val speedKb: Long)

    /**
     * 并发探测候选地址，返回拉流最快的一条；全都不通返回 null。
     *
     * items 第一个元素是地址，第二个元素是「优先级」（由 PlayerFragment 按运营商/省份算出来）：
     *   0 = 同运营商 + 同省（最优：同一个骨干区域，延迟和丢包都最小）
     *   1 = 同运营商（同网不同省）
     *   2 = 同省不同网
     *   3 = 跨网跨省
     * 先只测 0/1 这两档：跨网地址即使首包很快，实际拉流也常常只有几十 KB/s。
     * 本网全不通才回退测剩下的，避免把已经测过的地址再测一遍。
     */
    suspend fun fastest(items: List<Pair<String, Int>>): Result? =
        withContext(Dispatchers.IO) {
            val preferred = items.indices.filter { items[it].second <= 1 }
            if (preferred.isNotEmpty()) {
                probeIndices(items, preferred)?.let { return@withContext it }
                Logger.i(TAG, "本网 ${preferred.size} 条都不够快，回退测其余 ${items.size - preferred.size} 条")
            }
            probeIndices(items, items.indices.filter { items[it].second > 1 })
                ?: probeIndices(items, items.indices.filter { items[it].second <= 1 })
        }

    private suspend fun probeIndices(
        items: List<Pair<String, Int>>,
        idxs: List<Int>,
    ): Result? {
        if (idxs.isEmpty()) return null
        val scored = coroutineScope {
            idxs.map { i ->
                async { semaphore.withPermit { i to probe(items[i].first) } }
            }.awaitAll()
        }
        return scored.mapNotNull { (i, s) -> s?.let { i to it } }
            .maxByOrNull { it.second.speedKb }
            ?.let { Result(it.first, it.second.elapsedMs, it.second.speedKb) }
    }

    /**
     * 测一条地址：先解出真实分片（m3u8 要跳两层：master → variant → 分片），
     * 再拉一段数据算吞吐。返回 null 表示这条播不了。
     */
    private fun probe(url: String): Score? {
        if (url.isBlank()) return null
        val started = System.currentTimeMillis()
        return try {
            val segment = resolveSegment(url) ?: return null
            measure(segment, started)
        } catch (e: Exception) {
            Logger.i(TAG, "probe fail ${e.javaClass.simpleName} $url")
            null
        }
    }

    /**
     * m3u8 → 真实分片地址。
     * 取第一个非注释行即为第一个 URI：媒体清单里它是分片，主清单里它是子清单（再解一层）。
     * 地址本身就是分片/直链的，直接返回原地址。
     */
    private fun resolveSegment(url: String, depth: Int = 2): String? {
        return try {
            val request = Request.Builder().url(url).build()
            probeClient.newCall(request).execute().use { resp ->
                if (resp.code !in 200..399) {
                    Logger.i(TAG, "probe ${resp.code} $url")
                    return null
                }
                val head = resp.peekBody(64L * 1024).string()
                if (!head.trimStart().startsWith("#EXTM3U")) {
                    // 不是清单，本身就是可测的媒体数据
                    url
                } else {
                    val next = firstUri(head, url)
                    if (next == null) {
                        Logger.i(TAG, "清单里没有可用分片 $url")
                        null
                    } else if (depth > 0 && looksLikePlaylist(next)) {
                        resolveSegment(next, depth - 1)
                    } else {
                        next
                    }
                }
            }
        } catch (e: Exception) {
            Logger.i(TAG, "probing ${e.javaClass.simpleName} $url")
            null
        }
    }

    private fun looksLikePlaylist(u: String): Boolean =
        u.contains(".m3u8") || u.contains(".m3u")

    /** 取清单里第一个非注释行（跳过 #EXT-X-STREAM-INF / #EXT-X-MEDIA 等标签） */
    private fun firstUri(playlist: String, base: String): String? {
        for (raw in playlist.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            return absolutize(base, line)
        }
        return null
    }

    private fun absolutize(base: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return try {
            java.net.URI(base).resolve(ref).toString()
        } catch (e: Exception) {
            ref
        }
    }

    /** 拉一段真实数据算吞吐：拉够 192 KB 或测满 1.2 s 就收工 */
    private fun measure(url: String, started: Long): Score? {
        val request = Request.Builder().url(url).build()
        return probeClient.newCall(request).execute().use { resp ->
            if (resp.code !in 200..399) {
                Logger.i(TAG, "measure ${resp.code} $url")
                return null
            }
            val stream: InputStream = resp.body?.byteStream() ?: return null
            var total = 0L
            val buf = ByteArray(READ_BUF)
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                total += n
                val elapsed = System.currentTimeMillis() - started
                if (total >= MEASURE_TARGET_BYTES || elapsed >= MEASURE_WINDOW_MS) break
            }
            val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1)
            if (total < MIN_BYTES) {
                Logger.i(TAG, "拉流不足 ${total / 1024} KB / ${elapsed} ms $url")
                return null
            }
            val speedKb = total / 1024 * 1000 / elapsed
            Score(elapsed, speedKb)
        }
    }
}
