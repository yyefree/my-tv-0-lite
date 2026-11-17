package com.lizongying.mytv0

import android.util.Log

/**
 * 统一日志出口。
 *
 * 为什么要包一层：全项目原有 171 处 Log 调用（Log.i/Log.d 为主），release 包里这些日志
 * 照样会执行，字符串拼接 + logcat 写入在这台 424MB 内存的老电视上是纯开销，
 * 而且电视端问题排查时 logcat 被这些 info 冲掉，真正的错误反而不好找。
 *
 * 规则：i/d 只在可调试包里输出；w/e 永远输出（错误信息必须留下）。
 * verbose 由 MyTVApplication.onCreate 按 ApplicationInfo.FLAG_DEBUGGABLE 设置，
 * 不依赖 BuildConfig（本工程没有开启 buildConfig 特性）。
 */
object Logger {
    @Volatile
    var verbose: Boolean = false

    fun d(tag: String, msg: String) {
        if (verbose) Log.d(tag, msg)
    }

    fun i(tag: String, msg: String) {
        if (verbose) Log.i(tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
    }

    fun w(tag: String, msg: String, e: Throwable) {
        Log.w(tag, msg, e)
    }

    fun e(tag: String, msg: String) {
        Log.e(tag, msg)
    }

    fun e(tag: String, msg: String, e: Throwable) {
        Log.e(tag, msg, e)
    }
}
