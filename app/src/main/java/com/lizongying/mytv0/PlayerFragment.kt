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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch


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

    // 测速优选用的协程作用域，Fragment 销毁时取消
    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 同一个台最多发起几次测速，防止「探测 → 换地址 → 又失败 → 再探测」死循环
    private var probeTried = 0
    private val probeTriedMax = 2
    private var probeTriedFor: String? = null

    private val startWatchdogRunnable = Runnable {
        val tv = tvModel ?: return@Runnable
        val p = player ?: return@Runnable
        if (p.playbackState == Player.STATE_READY) {
            return@Runnable
        }
        if (probeAndSwitch(tv, "起播超时 ${delayStartWatchdog}ms")) {
            return@Runnable
        }
        handleUnavailable(tv, "起播超时 ${delayStartWatchdog}ms")
    }

    /**
     * 起播失败或超时后，不再按顺序一条条试（每条都要等 3~20 s 超时，用户看到的是十几秒黑屏），
     * 改成对全部地址并发测首包，直接跳到最快能用的那条。返回 true 表示已经发起切换。
     */
    private fun probeAndSwitch(tv: TVModel, reason: String): Boolean {
        val uris = tv.tv.uris
        if (uris.size <= 1) {
            return false
        }
        if (probeTried >= probeTriedMax) {
            Logger.i(TAG, "${tv.tv.title} $reason，测速次数已用尽，改回按顺序切")
            return false
        }
        probeTried++
        // 当前这条已经失败了，之前的测速缓存也不再可信，先清掉
        SourceProbe.forget(tv.tv.title)
        // 当前这条已经失败了，只测其余的
        val others = uris.indices.filter { it != tv.videoIndexValue }
        if (others.isEmpty()) {
            return false
        }
        Logger.i(
            TAG,
            "${tv.tv.title} $reason，开始测速（第 $probeTried/$probeTriedMax 次，共 ${others.size} 条候选，" +
                "本网=${IspDetector.label()}）"
        )
        // 本网 + 同省地址优先：移动宽带优先走移动 CDN，本省地址又比跨省少跳几段骨干
        val wantIsp = IspDetector.wantIsp()
        val wantRegion = IspDetector.wantRegion()
        probeScope.launch {
            val items = others.map { i ->
                val ispHit = wantIsp != IspDetector.Isp.UNKNOWN &&
                    tv.tv.uriIsp.getOrNull(i) == IspDetector.codeOf(wantIsp)
                val regHit = wantRegion.isNotEmpty() &&
                    tv.tv.uriRegion.getOrNull(i) == wantRegion
                val prio = when {
                    ispHit && regHit -> 0  // 同网同省，最该先用
                    ispHit -> 1            // 同网跨省
                    regHit -> 2            // 同省跨网
                    else -> 3              // 跨网跨省
                }
                uris[i] to prio
            }
            val best = SourceProbe.fastest(items)
            // 探测期间用户可能已经换台，结果作废
            if (tvModel?.tv?.title != tv.tv.title) {
                return@launch
            }
            if (best == null) {
                Logger.i(TAG, "${tv.tv.title} 测速：没有一条能通")
                handleUnavailable(tv, "$reason（测速无可用地址）")
                return@launch
            }
            val realIndex = others[best.index]
            SourceProbe.remember(tv.tv.title, realIndex)
            Logger.i(TAG, "${tv.tv.title} 测速选中第 $realIndex 条（首包 ${best.costMs} ms）")
            tv.selectVideo(realIndex)
            tv.setReady(true)
        }
        return true
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
                    // 有备用地址时不按顺序一个个试（每条都要等 3~20 s 超时），
                    // 直接并发测速跳到最快那条；只有单地址、或测速次数用尽才判死。
                    if (probeAndSwitch(tv, "网络错误 code=$code")) {
                        Logger.i(TAG, "${tv.tv.title} 网络错误 code=$code，转入测速优选")
                    } else {
                        Logger.i(TAG, "${tv.tv.title} 网络错误 code=$code，无可切换地址")
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
        // 只有换台才重置测速计数；同一个台内部重播（setReady）不能重置，否则会无限探测
        if (probeTriedFor != tvModel.tv.title) {
            probeTriedFor = tvModel.tv.title
            probeTried = 0
        }
        // 这个台之前测过哪条地址最快，直接用它，省掉一轮黑屏。
        // 只在「本台第一次播放」时用：一旦已经失败过（probeTried>0），缓存就可能是错的那条，
        // 再套用会和 handleUnavailable 的按顺序切换互相覆盖，形成 9 秒一轮的无限重试。
        if (probeTried == 0) {
            SourceProbe.cachedIndex(tvModel.tv.title)?.let { best ->
                if (best in tvModel.tv.uris.indices && best != tvModel.videoIndexValue) {
                    Logger.i(TAG, "${tvModel.tv.title} 使用测速缓存地址 #$best")
                    tvModel.selectVideo(best)
                }
            }
        }
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
        probeScope.cancel()
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