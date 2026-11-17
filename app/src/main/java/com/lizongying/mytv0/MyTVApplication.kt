package com.lizongying.mytv0

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import java.util.Locale

class MyTVApplication : Application() {

    companion object {
        private const val TAG = "MyTVApplication"
        private lateinit var instance: MyTVApplication

        @JvmStatic
        fun getInstance(): MyTVApplication {
            return instance
        }
    }

    private lateinit var displayMetrics: DisplayMetrics
    private lateinit var realDisplayMetrics: DisplayMetrics

    private var width = 0
    private var height = 0
    private var shouldWidth = 0
    private var shouldHeight = 0
    private var ratio = 1.0
    private var density = 2.0f
    private var scale = 1.0f

    lateinit var imageHelper:ImageHelper

    override fun onCreate() {
        super.onCreate()
        instance = this

        // release 包（debuggable=false）里关掉 Logger.i/d：这台电视内存只有 424MB，
        // 171 处 info 日志的字符串拼接 + logcat 写入是纯开销，还会把真正的错误冲掉。
        Logger.verbose =
            (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        displayMetrics = DisplayMetrics()
        realDisplayMetrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        windowManager.defaultDisplay.getRealMetrics(realDisplayMetrics)

        if (realDisplayMetrics.heightPixels > realDisplayMetrics.widthPixels) {
            width = realDisplayMetrics.heightPixels
            height = realDisplayMetrics.widthPixels
        } else {
            width = realDisplayMetrics.widthPixels
            height = realDisplayMetrics.heightPixels
        }

        density = Resources.getSystem().displayMetrics.density
        scale = displayMetrics.scaledDensity

        if ((width.toDouble() / height) < (16.0 / 9.0)) {
            ratio = width * 2 / 1920.0 / density
            shouldWidth = width
            shouldHeight = (width * 9.0 / 16.0).toInt()
        } else {
            ratio = height * 2 / 1080.0 / density
            shouldHeight = height
            shouldWidth = (height * 16.0 / 9.0).toInt()
        }

        Thread.setDefaultUncaughtExceptionHandler(MyTVExceptionHandler(this))

        imageHelper = ImageHelper(this)
    }

    fun getDisplayMetrics(): DisplayMetrics {
        return displayMetrics
    }

    fun toast(message: CharSequence = "", duration: Int = Toast.LENGTH_SHORT) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, duration).show()
        }
    }

    fun shouldWidthPx(): Int {
        return shouldWidth
    }

    fun shouldHeightPx(): Int {
        return shouldHeight
    }

    fun dp2Px(dp: Int): Int {
        return (dp * ratio * density + 0.5f).toInt()
    }

    fun px2Px(px: Int): Int {
        return (px * ratio + 0.5f).toInt()
    }

    fun px2PxFont(px: Float): Float {
        return (px * ratio / scale).toFloat()
    }

    fun sp2Px(sp: Float): Float {
        return (sp * ratio * scale).toFloat()
    }

    override fun attachBaseContext(base: Context) {
        try {
            // 原实现强制繁体中文（Locale.TRADITIONAL_CHINESE），大陆用户的电视上全是繁体字，改简体。
            // 注意：必须基于 base 的现有 configuration 改 locale，用空的 Configuration() 会让
            // 资源匹配其它字段全部丢失，结果退回英文（表现为界面中英混杂）。
            val locale = Locale.SIMPLIFIED_CHINESE
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                super.attachBaseContext(base.createConfigurationContext(config))
            } else {
                val resources = base.resources
                @Suppress("DEPRECATION")
                resources.updateConfiguration(config, resources.displayMetrics)
                super.attachBaseContext(base)
            }
        } catch (_: Exception) {
            super.attachBaseContext(base)
        }
    }
}