package com.lizongying.mytv0.requests

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * 直播源挂了就是挂了。
 *
 * 默认的 DefaultLoadErrorHandlingPolicy 会对网络错误退避重试多轮，一个连不上的地址
 * 在 ExoPlayer 内部要拖 40 s 才会把错误冒泡到 onPlayerError，用户看到的就是长时间黑屏。
 *
 * 这里只给 1 次、间隔 500 ms 的重试：
 *  - 死源：约「连接超时 + 0.5 s + 连接超时」就报错（实测 4~10 s，上游 47 s）；
 *  - 偶发一次抖包的活源（实测 CCTV-1 首次请求会超时、第二次立刻成功）仍能救回来。
 */
class FastFailPolicy : LoadErrorHandlingPolicy {

    private fun isManifest(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Boolean =
        loadErrorInfo.mediaLoadData.dataType == C.DATA_TYPE_MANIFEST

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (isManifest(loadErrorInfo)) 300L else 1000L

    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        if (dataType == C.DATA_TYPE_MANIFEST) 2 else 3

    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
    ): LoadErrorHandlingPolicy.FallbackSelection? = null
}
