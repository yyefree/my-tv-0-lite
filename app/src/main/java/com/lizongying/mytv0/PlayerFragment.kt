package com.lizongying.mytv0

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.lizongying.mytv0.data.SourceType
import com.lizongying.mytv0.databinding.PlayerBinding
import com.lizongying.mytv0.models.TVModel


class PlayerFragment : Fragment() {
    private var _binding: PlayerBinding? = null
    private val binding get() = _binding!!

    private var player: ExoPlayer? = null

    private var tvModel: TVModel? = null
    private val aspectRatio = 16f / 9f

    private val handler = Handler(Looper.myLooper()!!)
    private val delayHideVolume = 2 * 1000L

    // 起播看门狗：换台后超过这个时间还没真正出画，就当这条地址不可用。
    // ExoPlayer 自己的退避重试会拖到 40 s 才把错误冒出来，用户看到的是长时间黑屏。
    private val delayStartWatchdog = 9 * 1000L

    // 单源台连续不可用时自动跳台的次数上限
    private var autoSkipCount = 0
    private val autoSkipMax = 3

    private val startWatchdogRunnable = Runnable {
        val tv = tvModel ?: return@Runnable
        val p = player ?: return@Runnable
        if (p.playbackState == Player.STATE_READY) {
            return@Runnable
        }
        handleUnavailable(tv, "起播超时 ${delayStartWatchdog}ms")
    }

    // 一条地址彻底不可用时的统一出口：先换该台的备用地址，没有就自动跳到下一个频道。
    // 自动跳最多连跳 3 台，避免整张表都挂掉时在台上疯狂跳转。
    private fun handleUnavailable(tv: TVModel, reason: String) {
        if (!tv.isLastVideo()) {
            tv.nextVideo()
            tv.setReady(true)
            tv.retryTimes = 0
            return
        }
        if (autoSkipCount < autoSkipMax) {
            autoSkipCount++
            Logger.i(TAG, "${tv.tv.title} $reason，自动跳到下一个频道（第 $autoSkipCount 次）")
            (activity as? MainActivity)?.next()
            return
        }
        Logger.i(TAG, "${tv.tv.title} $reason，连续 ${autoSkipMax} 台不可用，停止跳转")
        tv.setErrInfo(R.string.play_error.getString())
    }

    private fun armStartWatchdog() {
        handler.removeCallbacks(startWatchdogRunnable)
        handler.postDelayed(startWatchdogRunnable, delayStartWatchdog)
    }

    private fun cancelStartWatchdog() {
        handler.removeCallbacks(startWatchdogRunnable)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = PlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updatePlayer()
        (activity as MainActivity).ready(TAG)
    }

    @OptIn(UnstableApi::class)
    fun updatePlayer() {
        if (context == null) {
            Logger.e(TAG, "context == null")
            return
        }

        val ctx = requireContext()

        val playerView = binding.playerView

        val renderersFactory = DefaultRenderersFactory(ctx)
        val playerMediaCodecSelector = PlayerMediaCodecSelector()
        renderersFactory.setMediaCodecSelector(playerMediaCodecSelector)
        renderersFactory.setExtensionRendererMode(
            if (SP.softDecode) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        )

        if (player != null) {
            player?.release()
        }

        // 这台电视只有 424 MB 内存，ExoPlayer 默认 50s 缓冲区对直播没有意义，
        // 改成 8s/20s 窗口：起播更快、换台更利索，也少占几十 MB 内存。
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(8000, 20000, 800, 1500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        player = ExoPlayer.Builder(ctx)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .build()
        player?.repeatMode = REPEAT_MODE_ALL
        player?.playWhenReady = true
        player?.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val ratio = playerView.measuredWidth.div(playerView.measuredHeight)
                val layoutParams = playerView.layoutParams
                if (ratio < aspectRatio) {
                    layoutParams?.height =
                        (playerView.measuredWidth.div(aspectRatio)).toInt()
                    playerView.layoutParams = layoutParams
                } else if (ratio > aspectRatio) {
                    layoutParams?.width =
                        (playerView.measuredHeight.times(aspectRatio)).toInt()
                    playerView.layoutParams = layoutParams
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)

                if (tvModel == null) {
                    Logger.e(TAG, "tvModel == null")
                    return
                }

                val tv = tvModel!!

                if (isPlaying) {
                    cancelStartWatchdog()
                    autoSkipCount = 0
                    tv.confirmSourceType()
                    tv.confirmVideoIndex()
                    tv.setErrInfo("")
                    tv.retryTimes = 0
                } else {
                    Logger.i(TAG, "${tv.tv.title} 播放停止")
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    (activity as MainActivity).onPlayEnd()
                }
                super.onPositionDiscontinuity(oldPosition, newPosition, reason)
            }

            override fun onPlayerError(error: PlaybackException) {
                super.onPlayerError(error)

                if (tvModel == null) {
                    Logger.e(TAG, "tvModel == null")
                    return
                }

                val tv = tvModel!!

                cancelStartWatchdog()

                Logger.i(
                    TAG,
                    "${tv.tv.title} onPlayerError ${error.errorCodeName}(${error.errorCode}) cause=${error.cause}"
                )

                // 源挂了就是挂了。网络类错误（连不上 / 超时 / HTTP 非 2xx）对同一个地址
                // 重试没有意义：上游 3 次 × 每次约 23 s（连接 5 s + 读 8 s × ExoPlayer 内部重试）
                // = 47 s 黑屏才报错。这里直接判死该地址，有备用地址就换，没有立刻出错误态。
                val code = error.errorCode
                val isNetworkError =
                    code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                            code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                            code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                            code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                            code == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                            code == PlaybackException.ERROR_CODE_TIMEOUT

                if (isNetworkError) {
                    // 注释说「有备用地址就换」，但原来这里直接 handleUnavailable，
                    // 把同一个频道后面的可用地址全放弃了 —— 表现为 CCTV-1 主源挂了就黑屏，
                    // 明明还有个备用地址能播。现在先把同频道的下一个地址试一遍，全试完才判死。
                    if (!tv.isLastVideo()) {
                        tv.nextVideo()
                        tv.setReady(true)
                        Logger.i(
                            TAG,
                            "${tv.tv.title} 网络错误 code=$code，换下一个地址 ${tv.videoIndex.value}"
                        )
                    } else {
                        Logger.i(TAG, "${tv.tv.title} 网络错误 code=$code，该频道地址已全部失败")
                        handleUnavailable(tv, "网络错误 code=$code")
                    }
                } else if (tv.retryTimes < tv.retryMaxTimes) {
                    var last = true
                    if (tv.getSourceTypeDefault() == SourceType.UNKNOWN) {
                        last = tv.nextSourceType()
                    }
                    tv.setReady(true)
                    if (last) {
                        tv.retryTimes++
                    }
                    Logger.i(
                        TAG,
                        "retry ${tv.videoIndex.value} ${tv.getSourceTypeCurrent()} ${tv.retryTimes}/${tv.retryMaxTimes}"
                    )
                } else {
                    if (!tv.isLastVideo()) {
                        tv.nextVideo()
                        tv.setReady(true)
                        tv.retryTimes = 0
                    } else {
                        handleUnavailable(tv, "已重试 ${tv.retryTimes} 次")
                    }
                }
            }
        })

        playerView.player = player
        tvModel?.let {
            play(it)
        }
    }

    @OptIn(UnstableApi::class)
    fun play(tvModel: TVModel) {
        this.tvModel = tvModel
        player?.run {
            tvModel.getVideoUrl() ?: return

            while (true) {
                val last = tvModel.isLastVideo()
                val mediaItem = tvModel.getMediaItem()
                if (mediaItem == null) {
                    if (last) {
                        tvModel.setErrInfo(R.string.play_error.getString())
                        break
                    }
                    tvModel.nextVideo()
                    continue
                }
                val mediaSource = tvModel.getMediaSource()
                if (mediaSource != null) {
                    setMediaSource(mediaSource)
                } else {
                    setMediaItem(mediaItem)
                }
                prepare()
                armStartWatchdog()
                break
            }
        }
    }

    @OptIn(UnstableApi::class)
    class PlayerMediaCodecSelector : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean
        ): MutableList<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
            val infos = MediaCodecUtil.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )
            if (mimeType == MimeTypes.VIDEO_H265 && !requiresSecureDecoder && !requiresTunnelingDecoder) {
                if (infos.isNotEmpty()) {
                    val infosNew = infos.find { it.name == "c2.android.hevc.decoder" }
                        ?.let { mutableListOf(it) }
                    if (infosNew != null) {
                        return infosNew
                    }
                }
            }
            return infos
        }
    }

    fun showVolume(visibility: Int) {
        binding.icon.visibility = visibility
        binding.volume.visibility = visibility
        hideVolume()
    }

    fun setVolumeMax(volume: Int) {
        binding.volume.max = volume
    }

    fun setVolume(progress: Int, volume: Boolean = false) {
        val context = requireContext()
        binding.volume.progress = progress
        binding.icon.setImageDrawable(
            ContextCompat.getDrawable(
                context,
                if (volume) {
                    if (progress > 0) R.drawable.volume_up_24px else R.drawable.volume_off_24px
                } else {
                    R.drawable.light_mode_24px
                }
            )
        )
    }

    fun hideVolume() {
        handler.removeCallbacks(hideVolumeRunnable)
        handler.postDelayed(hideVolumeRunnable, delayHideVolume)
    }

    fun hideVolumeNow() {
        handler.removeCallbacks(hideVolumeRunnable)
        handler.postDelayed(hideVolumeRunnable, 0)
    }

    private val hideVolumeRunnable = Runnable {
        binding.icon.visibility = View.GONE
        binding.volume.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        if (player?.isPlaying == false) {
            player?.prepare()
            player?.play()
        }
    }

    override fun onPause() {
        super.onPause()
        if (player?.isPlaying == true) {
            player?.stop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "PlayerFragment"
    }
}