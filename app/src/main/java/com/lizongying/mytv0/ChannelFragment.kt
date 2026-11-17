package com.lizongying.mytv0

import MainViewModel
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.marginEnd
import androidx.core.view.marginTop
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.lizongying.mytv0.databinding.ChannelBinding
import com.lizongying.mytv0.models.TVModel

class ChannelFragment : Fragment() {
    private var _binding: ChannelBinding? = null
    private val binding get() = _binding!!

    private val handler = Handler(Looper.getMainLooper())
    // 频道号浮层停留时间（只负责把数字藏掉）
    private val delay: Long = 5000
    // 输入完最后一位后等多久开始跳台。上游 5 s，按完数字要干等 5 秒才有反应；
    // 电视上输 1~3 位数字通常 1 秒内完成，这里给 1.3 s。
    private val delayInput: Long = 1300
    private var channel = 0
    private var channelCount = 0

    private lateinit var viewModel: MainViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ChannelBinding.inflate(inflater, container, false)
        _binding!!.root.visibility = View.GONE

        val application = requireActivity().applicationContext as MyTVApplication

        binding.channel.layoutParams.width = application.px2Px(binding.channel.layoutParams.width)
        binding.channel.layoutParams.height = application.px2Px(binding.channel.layoutParams.height)

        val layoutParams = binding.channel.layoutParams as ViewGroup.MarginLayoutParams
        layoutParams.topMargin = application.px2Px(binding.channel.marginTop)
        layoutParams.marginEnd = application.px2Px(binding.channel.marginEnd)
        binding.channel.layoutParams = layoutParams

        binding.content.textSize = application.px2PxFont(binding.content.textSize)
        binding.time.textSize = application.px2PxFont(binding.time.textSize)

        binding.main.layoutParams.width = application.shouldWidthPx()
        binding.main.layoutParams.height = application.shouldHeightPx()

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireActivity()
        viewModel = ViewModelProvider(context)[MainViewModel::class.java]
    }

    fun show(tvModel: TVModel) {
        val tv = tvModel.tv
        Log.i(TAG, "show $tv")
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(playRunnable)
        if (_binding != null) {
            binding.content.text =
                if (tv.number == -1) (tv.id.plus(1)).toString() else tv.number.toString()
        }
        view?.visibility = View.VISIBLE
        channel = 0
        channelCount = 0
        handler.postDelayed(hideRunnable, delay)
    }

    fun show(channel: Int) {
        Log.i(TAG, "input $channel ${this.channel}")
        // App 刚启动、本 Fragment 还没 attach 到 Activity 时按数字键会走到这里，
        // 此时 viewModel 尚未在 onViewCreated 里初始化，直接访问会抛
        // "lateinit property viewModel has not been initialized" 并把进程崩掉。
        if (!::viewModel.isInitialized) {
            Log.i(TAG, "viewModel not ready, drop input $channel")
            return
        }
        val tv = viewModel.groupModel.getCurrent()?.tv ?: return
        if (tv.id > 10 && tv.id == this.channel - 1) {
            this.channel = 0
            channelCount = 0
        }
        if (channelCount > 2) {
            return
        }
        channelCount++
        this.channel = "${this.channel}$channel".toInt()
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(playRunnable)
        Log.d(TAG, "channelCount $channelCount")
        if (_binding != null) {
            binding.content.text = "${this.channel}"
        }
        view?.visibility = View.VISIBLE
        if (channelCount < 3) {
            handler.postDelayed(playRunnable, delayInput)
        } else {
            playNow()
        }
    }

    fun playNow() {
        handler.postDelayed(playRunnable, 0)
    }

    override fun onResume() {
        super.onResume()
        if (view?.visibility == View.VISIBLE) {
            handler.postDelayed(hideRunnable, delay)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(playRunnable)
    }

    private val hideRunnable = Runnable {
        if (_binding != null) {
            binding.content.text = BLANK
        }

        view?.visibility = View.GONE
    }

    fun hideSelf() {
        channel = 0
        channelCount = 0
        handler.postDelayed(hideRunnable, 0)
    }

    private val playRunnable = Runnable {
        // 频道号 -> 全局位置。以前这里用 tv.id，但 id 只是「全部频道」组内的下标，
        // 前面还有一个「我的收藏」组，getPosition() 把两组串起来计数，于是差一个收藏数，
        // 表现就是按 5 落到 CCTV-4。
        val position = viewModel.groupModel.findPositionByNumber(channel)
        val c = if (position >= 0) position else -1
        if ((activity as MainActivity).play(c)) {
            channel = 0
            channelCount = 0
            handler.postDelayed(hideRunnable, delay)
        } else {
            hideSelf()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "ChannelFragment"
        private const val BLANK = ""
    }
}