package com.lizongying.mytv0

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * 只给音频挂 FFmpeg 渲染器，视频照旧走硬解。
 *
 * 为什么需要这个：带 MPEG-1 Layer II 音轨的老直播流（`audio/mpeg-L2`）在部分电视芯片上
 * 解不出来。实测 FFmpeg 扩展本身是能解 MP2 的——`FfmpegAudioRenderer.supportsFormat`
 * 对 `audio/mpeg-L2` 返回 172，含 `FORMAT_HANDLED` 位；但它同时带了
 * `FORMAT_EXCEEDS_RENDERER_CAPABILITIES`，于是 DefaultTrackSelector 把 MediaCodec
 * 排到前面（后者声称支持 MP2，初始化却报 -49999）。结果就是「软解开关打开了，
 * 音频仍然走硬解然后失败」。
 *
 * 这里在软解台上干脆不注册 MediaCodec 音频渲染器，让 MP2 一定落到 FFmpeg。
 * 只影响被标记为软解的台，其余频道完全不动。
 */
@OptIn(UnstableApi::class)
class FfmpegOnlyAudioFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
    }
}
