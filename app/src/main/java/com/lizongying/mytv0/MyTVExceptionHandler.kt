package com.lizongying.mytv0

import android.content.Context
import android.os.Build
import android.util.Log
import com.lizongying.mytv0.requests.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.system.exitProcess

class MyTVExceptionHandler(val context: Context) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        val crashInfo =
            "APP: ${context.appVersionName}, PRODUCT: ${Build.PRODUCT}, DEVICE: ${Build.DEVICE}, SUPPORTED_ABIS: ${Build.SUPPORTED_ABIS.joinToString()}, BOARD: ${Build.BOARD}, MANUFACTURER: ${Build.MANUFACTURER}, MODEL: ${Build.MODEL}, VERSION: ${Build.VERSION.SDK_INT}\nThread: ${t.name}\nException: ${e.message}\nStackTrace: ${
                Log.getStackTraceString(
                    e
                )
            }\n"

        runBlocking {
            launch {
                saveCrashInfoToFile(crashInfo)

                withContext(Dispatchers.Main) {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    exitProcess(1)
                }
            }
        }
    }

    private suspend fun saveCrashInfoToFile(crashInfo: String) {
        // 本地始终留一份最近一次崩溃。原来只往作者服务器上报，
        // 这台电视外网时通时不通，上报失败就等于什么都没留下，出问题只能靠猜。
        withContext(Dispatchers.IO) {
            try {
                val file = java.io.File(context.filesDir, "crash_last.txt")
                file.writeText(crashInfo)
                Logger.e(TAG, "崩溃堆栈已写本地 ${file.absolutePath}")
            } catch (e: Exception) {
                Logger.e(TAG, "写本地崩溃日志失败", e)
            }
        }

        if (isLimit()) {
            Logger.e(TAG, crashInfo)
        } else {
            try {
                saveLog(crashInfo)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun isLimit(): Boolean {
        if (context.appVersionName != SP.version) {
            SP.version = context.appVersionName
            SP.logTimes = SP.DEFAULT_LOG_TIMES
            return false
        } else {
            SP.logTimes--
            return SP.logTimes < 0
        }
    }

    private suspend fun saveLog(crashInfo: String) {
        withContext(Dispatchers.IO) {
            try {
                val request = okhttp3.Request.Builder()
                    .url("https://lyrics.run/my-tv-0/v1/log")
                    .method("POST", crashInfo.toRequestBody("text/plain".toMediaType()))
                    .build()

                HttpClient.okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Logger.i(TAG, "log success")
                    } else {
                        Logger.e(TAG, "log failed: ${response.codeAlias()}")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    companion object {
        private const val TAG = "MyTVException"
    }
}