package com.lizongying.mytv0

import MainViewModel
import MainViewModel.Companion.CACHE_FILE_NAME
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.marginBottom
import androidx.core.view.marginEnd
import androidx.core.view.marginTop
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.lizongying.mytv0.ModalFragment.Companion.KEY_URL
import com.lizongying.mytv0.SimpleServer.Companion.PORT
import com.lizongying.mytv0.databinding.SettingBinding
import kotlin.math.max
import kotlin.math.min


class SettingFragment : Fragment() {

    private var _binding: SettingBinding? = null
    private val binding get() = _binding!!

    private lateinit var uri: Uri

    private lateinit var updateManager: UpdateManager

    // 局域网配置页地址，带访问令牌（否则 /api/* 会被 403，见 SimpleServer.serve）
    private val server: String
        get() = "http://${PortUtil.lan()}:$PORT/?token=${SP.serverToken}"

    private lateinit var viewModel: MainViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val application = requireActivity().applicationContext as MyTVApplication
        val context = requireContext()
        val mainActivity = (activity as MainActivity)

        _binding = SettingBinding.inflate(inflater, container, false)

        binding.versionName.text = "v${context.appVersionName}"
        binding.version.text = "https://github.com/yyefree/my-tv-0-lite"

        val switchChannelReversal = _binding?.switchChannelReversal
        switchChannelReversal?.isChecked = SP.channelReversal
        switchChannelReversal?.setOnCheckedChangeListener { _, isChecked ->
            SP.channelReversal = isChecked
            mainActivity.settingActive()
        }

        val switchChannelNum = _binding?.switchChannelNum
        switchChannelNum?.isChecked = SP.channelNum
        switchChannelNum?.setOnCheckedChangeListener { _, isChecked ->
            SP.channelNum = isChecked
            mainActivity.settingActive()
        }

        val switchTime = _binding?.switchTime
        switchTime?.isChecked = SP.time
        switchTime?.setOnCheckedChangeListener { _, isChecked ->
            SP.time = isChecked
            mainActivity.settingActive()
        }

        val switchBootStartup = _binding?.switchBootStartup
        switchBootStartup?.isChecked = SP.bootStartup
        switchBootStartup?.setOnCheckedChangeListener { _, isChecked ->
            SP.bootStartup = isChecked
            mainActivity.settingActive()
        }

        val switchRepeatInfo = _binding?.switchRepeatInfo
        switchRepeatInfo?.isChecked = SP.repeatInfo
        switchRepeatInfo?.setOnCheckedChangeListener { _, isChecked ->
            SP.repeatInfo = isChecked
            mainActivity.settingActive()
        }

        val switchConfigAutoLoad = _binding?.switchConfigAutoLoad
        switchConfigAutoLoad?.isChecked = SP.configAutoLoad
        switchConfigAutoLoad?.setOnCheckedChangeListener { _, isChecked ->
            SP.configAutoLoad = isChecked
            mainActivity.settingActive()
        }

        val switchDefaultLike = _binding?.switchDefaultLike
        switchDefaultLike?.isChecked = SP.defaultLike
        switchDefaultLike?.setOnCheckedChangeListener { _, isChecked ->
            SP.defaultLike = isChecked
            mainActivity.settingActive()
        }

        val switchShowAllChannels = _binding?.switchShowAllChannels
        switchShowAllChannels?.isChecked = SP.showAllChannels

        val switchCompactMenu = _binding?.switchCompactMenu
        switchCompactMenu?.isChecked = SP.compactMenu
        switchCompactMenu?.setOnCheckedChangeListener { _, isChecked ->
            SP.compactMenu = isChecked
            mainActivity.updateMenuSize()
            mainActivity.settingActive()
        }

        val switchDisplaySeconds = _binding?.switchDisplaySeconds
        switchDisplaySeconds?.isChecked = SP.displaySeconds

        val switchSoftDecode = _binding?.switchSoftDecode
        switchSoftDecode?.isChecked = SP.softDecode
        switchSoftDecode?.setOnCheckedChangeListener { _, isChecked ->
            SP.softDecode = isChecked
            mainActivity.switchSoftDecode()
            mainActivity.settingActive()
        }

        binding.remoteSettings.setOnClickListener {
            val imageModalFragment = ModalFragment()
            val args = Bundle()
            args.putString(KEY_URL, server)
            imageModalFragment.arguments = args

            imageModalFragment.show(requireFragmentManager(), ModalFragment.TAG)
            mainActivity.settingActive()
        }

        binding.checkVersion.setOnClickListener {
            requestInstallPermissions()
            mainActivity.settingActive()
        }

        binding.confirmConfig.setOnClickListener {
            val sourcesFragment = SourcesFragment()

            sourcesFragment.show(requireFragmentManager(), SourcesFragment.TAG)
            mainActivity.settingActive()
        }

        binding.appreciate.setOnClickListener {
            val imageModalFragment = ModalFragment()

            val args = Bundle()
            args.putInt(ModalFragment.KEY_DRAWABLE_ID, R.drawable.appreciate)
            imageModalFragment.arguments = args

            imageModalFragment.show(requireFragmentManager(), ModalFragment.TAG)
            mainActivity.settingActive()
        }

        binding.setting.setOnClickListener {
            hideSelf()
        }

        // 「更多设置」展开/收起：面板 13 项一屏放不下，常用的留在外面，不常用的收在这里
        binding.moreSettings.setOnClickListener {
            val expanded = binding.moreGroup.visibility == View.VISIBLE
            binding.moreGroup.visibility = if (expanded) View.GONE else View.VISIBLE
            binding.moreSettings.text = if (expanded) "更多设置 ▾" else "收起设置 ▴"
            if (!expanded) {
                binding.switchShowAllChannels.requestFocus()
            }
            mainActivity.settingActive()
        }

        binding.exit.setOnClickListener {
            requireActivity().finishAffinity()
        }

        val txtTextSize =
            application.px2PxFont(binding.versionName.textSize)

        binding.content.layoutParams.width =
            application.px2Px(binding.content.layoutParams.width)
        binding.content.setPadding(
            application.px2Px(binding.content.paddingLeft),
            application.px2Px(binding.content.paddingTop),
            application.px2Px(binding.content.paddingRight),
            application.px2Px(binding.content.paddingBottom)
        )

        binding.name.textSize = application.px2PxFont(binding.name.textSize)
        binding.version.textSize = txtTextSize
        val layoutParamsVersion = binding.version.layoutParams as ViewGroup.MarginLayoutParams
        layoutParamsVersion.topMargin = application.px2Px(binding.version.marginTop)
        layoutParamsVersion.bottomMargin = application.px2Px(binding.version.marginBottom)
        binding.version.layoutParams = layoutParamsVersion

        val btnWidth =
            application.px2Px(binding.confirmConfig.layoutParams.width)

        val btnLayoutParams =
            binding.confirmConfig.layoutParams as ViewGroup.MarginLayoutParams
        btnLayoutParams.marginEnd = application.px2Px(binding.confirmConfig.marginEnd)

        binding.versionName.textSize = txtTextSize
        binding.moreSettings.textSize = txtTextSize

        for (i in listOf(
            binding.remoteSettings,
            binding.confirmConfig,
            binding.clear,
            binding.checkVersion,
            binding.exit,
            binding.appreciate,
        )) {
            i.layoutParams.width = btnWidth
            i.textSize = txtTextSize
            i.layoutParams = btnLayoutParams
            i.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    i.background = ColorDrawable(
                        ContextCompat.getColor(
                            context,
                            R.color.focus
                        )
                    )
                    i.setTextColor(
                        ContextCompat.getColor(
                            context,
                            R.color.white
                        )
                    )
                } else {
                    i.background = ColorDrawable(
                        ContextCompat.getColor(
                            context,
                            R.color.description_blur
                        )
                    )
                    i.setTextColor(
                        ContextCompat.getColor(
                            context,
                            R.color.blur
                        )
                    )
                }
            }
        }

        val textSizeSwitch = application.px2PxFont(binding.switchChannelReversal.textSize)

        val layoutParamsSwitch =
            binding.switchChannelReversal.layoutParams as ViewGroup.MarginLayoutParams
        layoutParamsSwitch.topMargin =
            application.px2Px(binding.switchChannelReversal.marginTop)

        for (i in listOf(
            binding.switchChannelReversal,
            binding.switchChannelNum,
            binding.switchTime,
            binding.switchBootStartup,
            binding.switchRepeatInfo,
            binding.switchConfigAutoLoad,
            binding.switchDefaultLike,
            binding.switchShowAllChannels,
            binding.switchCompactMenu,
            binding.switchDisplaySeconds,
            binding.switchSoftDecode,
        )) {
            i.textSize = textSizeSwitch
            i.layoutParams = layoutParamsSwitch
            i.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    i.setTextColor(
                        ContextCompat.getColor(
                            context,
                            R.color.focus
                        )
                    )
                } else {
                    i.setTextColor(
                        ContextCompat.getColor(
                            context,
                            R.color.title_blur
                        )
                    )
                }
            }
        }

        updateManager = UpdateManager(context, context.appVersionCode)

        // 状态行跟随屏幕缩放，和面板里其它文字保持一致
        binding.updateStatus.textSize = application.px2PxFont(binding.updateStatus.textSize)
        refreshUpdateStatus(UpdateManager.status.value ?: "")

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val context = requireActivity()
        val mainActivity = (activity as MainActivity)
        val application = context.applicationContext as MyTVApplication
        val imageHelper = application.imageHelper

        viewModel = ViewModelProvider(context)[MainViewModel::class.java]

        binding.switchDisplaySeconds.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setDisplaySeconds(isChecked)
        }

        binding.clear.setOnClickListener {
            // 恢复默认前先把当前配置存一份：配置只存在这台机器上，
            // 误点「恢复默认」等于把精心调好的源和代理全清空，重配很麻烦。
            backupConfig()

            SP.channelNum = SP.DEFAULT_CHANNEL_NUM

            SP.sources = SP.DEFAULT_SOURCES
            Logger.i(TAG, "DEFAULT_SOURCES ${SP.DEFAULT_SOURCES}")
            viewModel.sources.init()

            SP.channelReversal = SP.DEFAULT_CHANNEL_REVERSAL
            SP.time = SP.DEFAULT_TIME
            SP.bootStartup = SP.DEFAULT_BOOT_STARTUP
            SP.repeatInfo = SP.DEFAULT_REPEAT_INFO
            SP.configAutoLoad = SP.DEFAULT_CONFIG_AUTO_LOAD
            SP.proxy = SP.DEFAULT_PROXY

            imageHelper.clearImage()

            // 软解码开关只影响下一次播放，不需要在这里强制重建播放器
            SP.softDecode = SP.DEFAULT_SOFT_DECODE

            SP.configUrl = SP.DEFAULT_CONFIG_URL
            Logger.i(TAG, "config url: ${SP.configUrl}")
            context.deleteFile(CACHE_FILE_NAME)
            viewModel.reset(context)
            confirmConfig()

            SP.channel = SP.DEFAULT_CHANNEL
            Logger.i(TAG, "default channel: ${SP.channel}")
            confirmChannel()

            SP.deleteLike()
            Logger.i(TAG, "clear like")

//            SP.positionGroup = SP.DEFAULT_POSITION_GROUP
//            viewModel.groupModel.setPosition(SP.DEFAULT_POSITION_GROUP)
//            viewModel.groupModel.setPositionPlaying(SP.DEFAULT_POSITION_GROUP)

            SP.positionGroup = viewModel.groupModel.defaultPosition()
            viewModel.groupModel.initPosition()

            SP.position = SP.DEFAULT_POSITION
            Logger.i(TAG, "list position: ${SP.position}")
            val tvListModel = viewModel.groupModel.getCurrentList()
            tvListModel?.setPosition(SP.DEFAULT_POSITION)
            tvListModel?.setPositionPlaying(SP.DEFAULT_POSITION)

            viewModel.groupModel.setPositionPlaying()
            viewModel.groupModel.getCurrentList()?.setPositionPlaying()
            viewModel.groupModel.getCurrent()?.setReady()

            SP.showAllChannels = SP.DEFAULT_SHOW_ALL_CHANNELS
            SP.compactMenu = SP.DEFAULT_COMPACT_MENU

            viewModel.setDisplaySeconds(SP.DEFAULT_DISPLAY_SECONDS)

            SP.epg = SP.DEFAULT_EPG
            viewModel.updateEPG()

            R.string.config_restored.showToast()
        }

        binding.switchShowAllChannels.setOnCheckedChangeListener { _, isChecked ->
            SP.showAllChannels = isChecked
            viewModel.groupModel.setChange()

            mainActivity.settingActive()
        }

        binding.remoteSettings.requestFocus()

        // 后台自动升级的状态（检测中 / 未连通 / 正在下载 x% / 已下载待安装）实时显示
        UpdateManager.status.observe(viewLifecycleOwner) { refreshUpdateStatus(it) }
        // 线路识别是后台线程跑的，识别完再刷一次，面板上就能看到「线路 移动·淄博」
        IspDetector.labelLive.observe(viewLifecycleOwner) {
            refreshUpdateStatus(UpdateManager.status.value ?: "")
            refreshLineSettings()
        }

        // 线路 / 省份：遥控器点一下循环切换，不用进子页面
        binding.btnIsp.setOnClickListener {
            val cur = ISP_CYCLE.indexOf(SP.ispOverride).let { if (it < 0) 0 else it }
            SP.ispOverride = ISP_CYCLE[(cur + 1) % ISP_CYCLE.size]
            applyLineChange()
        }
        binding.btnRegion.setOnClickListener {
            val cur = REGION_CYCLE.indexOf(SP.regionOverride).let { if (it < 0) 0 else it }
            SP.regionOverride = REGION_CYCLE[(cur + 1) % REGION_CYCLE.size]
            applyLineChange()
        }
        refreshLineSettings()
    }

    private val ISP_CYCLE = listOf("", "MOBILE", "TELECOM", "UNICOM")
    private val REGION_CYCLE = listOf("", "山东", "浙江", "河北", "广东", "河南", "湖北", "江苏")

    /**
     * 两个按钮的文字。「自动」时把识别结果显示在括号里，
     * 这样用户一眼能看出「它到底判断成了什么」，判断错了才有人会去手动改。
     */
    private fun refreshLineSettings() {
        if (_binding == null) {
            return
        }
        val autoIsp = when (IspDetector.current()?.isp) {
            IspDetector.Isp.MOBILE -> "移动"
            IspDetector.Isp.TELECOM -> "电信"
            IspDetector.Isp.UNICOM -> "联通"
            else -> "未检测"
        }
        val manualIsp = when (SP.ispOverride) {
            "MOBILE" -> "移动"
            "TELECOM" -> "电信"
            "UNICOM" -> "联通"
            else -> ""
        }
        binding.btnIsp.text =
            if (manualIsp.isEmpty()) "线路：自动（$autoIsp）" else "线路：$manualIsp"

        val autoRegion = IspDetector.current()?.province.orEmpty().ifEmpty { "未检测" }
        binding.btnRegion.text =
            if (SP.regionOverride.isEmpty()) "省份：自动（$autoRegion）" else "省份：${SP.regionOverride}"
    }

    /** 改了线路或省份，之前按旧条件挑出来的地址作废，下次切台重新测 */
    private fun applyLineChange() {
        SourceProbe.clear()
        refreshLineSettings()
        refreshUpdateStatus(UpdateManager.status.value ?: "")
    }

    /**
     * 「更新应用」下面那行小字：后台自动升级的状态 + 上次检测时间。
     * 没有这一行的时候，每小时静默探测完全不可见，用户会以为功能坏了。
     */
    private fun refreshUpdateStatus(desc: String) {
        if (_binding == null) {
            return
        }
        val last = SP.updateLastCheck
        val time = if (last > 0) {
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
                .format(java.util.Date(last))
        } else {
            ""
        }
        val base = desc.ifEmpty { "尚未检测" }
        val timePart = if (time.isEmpty()) "" else " · 上次检测 $time"
        // 顺带把「当前出口是哪家宽带」露出来：多运营商源里 App 就是按它来挑本网地址的
        binding.updateStatus.text = "线路 ${IspDetector.label()} · $base$timePart"
    }

    /**
     * 把当前配置原样存到 files 里，文件名固定（只留最近一次）。
     * 电视上没有文件管理器，真要找回时用 `adb pull /data/data/com.lizongying.mytv0/files/config_backup.txt`。
     */
    private fun backupConfig() {
        try {
            val sb = StringBuilder()
            sb.append("backupAt=").append(System.currentTimeMillis()).append('\n')
            sb.append("config=").append(SP.configUrl ?: "").append('\n')
            sb.append("proxy=").append(SP.proxy ?: "").append('\n')
            sb.append("epg=").append(SP.epg ?: "").append('\n')
            sb.append("channel=").append(SP.channel).append('\n')
            sb.append("sources=").append(SP.sources ?: "").append('\n')
            sb.append("channelReversal=").append(SP.channelReversal).append('\n')
            sb.append("channelNum=").append(SP.channelNum).append('\n')
            sb.append("showTime=").append(SP.time).append('\n')
            sb.append("bootStartup=").append(SP.bootStartup).append('\n')
            sb.append("repeatInfo=").append(SP.repeatInfo).append('\n')
            sb.append("configAutoLoad=").append(SP.configAutoLoad).append('\n')

            val cache = java.io.File(requireContext().filesDir, CACHE_FILE_NAME)
            if (cache.exists()) {
                sb.append("channels=\n").append(cache.readText()).append('\n')
            }

            val file = java.io.File(requireContext().filesDir, "config_backup.txt")
            file.writeText(sb.toString())
            Logger.i(TAG, "已备份配置 ${file.absolutePath} (${file.length()} B)")
        } catch (e: Exception) {
            Logger.e(TAG, "备份配置失败", e)
        }
    }

    private fun confirmConfig() {
        if (SP.configUrl.isNullOrEmpty()) {
            Logger.w(TAG, "SP.configUrl is null or empty")
            return
        }

        uri = Uri.parse(Utils.formatUrl(SP.configUrl!!))
        if (uri.scheme == "") {
            uri = uri.buildUpon().scheme("http").build()
        }
        if (uri.isAbsolute) {
            if (uri.scheme == "file") {
                requestReadPermissions()
            } else {
                viewModel.importFromUri(uri)
            }
        } else {
            R.string.invalid_config_address.showToast()
        }
        (activity as MainActivity).settingActive()
    }

    private fun confirmChannel() {
        SP.channel =
            min(max(SP.channel, 0), viewModel.groupModel.getAllList()!!.size())

        (activity as MainActivity).settingActive()
    }

    private fun hideSelf() {
        requireActivity().supportFragmentManager.beginTransaction()
            .hide(this)
            .commitAllowingStateLoss()
        (activity as MainActivity).showTimeFragment()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (_binding != null && !hidden) {
            binding.remoteSettings.requestFocus()
            // 面板重新打开时刷新一次，能看到刚发生的那轮探测结果
            refreshUpdateStatus(UpdateManager.status.value ?: "")
        }
    }

    private fun checkAndAddPermission(context: Context, permission: String, permissionsList: MutableList<String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            permissionsList.add(permission)
        }
    }

    private fun requestInstallPermissions() {
        val context = requireContext()
        val permissionsList = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            permissionsList.add(Manifest.permission.REQUEST_INSTALL_PACKAGES)
        }

        checkAndAddPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE, permissionsList)
        checkAndAddPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE, permissionsList)

        if (permissionsList.isNotEmpty()) {
            Logger.i(TAG, "ask $permissionsList")
            ActivityCompat.requestPermissions(
                requireActivity(),
                permissionsList.toTypedArray(),
                PERMISSIONS_REQUEST_CODE
            )
        } else {
            updateManager.checkAndUpdate()
        }
    }

    private fun requestReadPermissions() {
        val context = requireContext()
        val permissionsList = mutableListOf<String>()

        checkAndAddPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE, permissionsList)

        if (permissionsList.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                permissionsList.toTypedArray(),
                PERMISSIONS_REQUEST_CODE
            )
        } else {
            viewModel.importFromUri(uri)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_READ_EXTERNAL_STORAGE_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                viewModel.importFromUri(uri)
            } else {
                R.string.authorization_failed.showToast()
            }
        }
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            var allPermissionsGranted = true
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false
                    break
                }
            }
            if (allPermissionsGranted) {
                updateManager.checkAndUpdate()
            } else {
                Logger.w(TAG, "ask permissions failed")
                R.string.authorization_failed.showToast()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SettingFragment"
        const val PERMISSIONS_REQUEST_CODE = 1
        const val PERMISSION_READ_EXTERNAL_STORAGE_REQUEST_CODE = 2
    }
}

