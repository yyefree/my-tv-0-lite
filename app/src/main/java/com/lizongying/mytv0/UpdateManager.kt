package com.lizongying.mytv0

import android.app.DownloadManager
import android.app.DownloadManager.Request
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.lizongying.mytv0.Utils.getUrls
import com.lizongying.mytv0.data.Global.gson
import com.lizongying.mytv0.data.ReleaseResponse
import com.lizongying.mytv0.requests.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONObject


class UpdateManager(
    private var context: Context,
    private var versionCode: Long
) :
    ConfirmationFragment.ConfirmationListener {

    private var downloadReceiver: DownloadReceiver? = null
    var release: ReleaseResponse? = null

    private suspend fun getRelease(): ReleaseResponse? {
        val urls = VERSION_URLS.flatMap { getUrls(it) }

        for (u in urls) {
            Log.i(TAG, "request $u")
            // 注意：以前这里直接 withContext { return@withContext ... }，返回值被丢弃，函数最后必然 return null，
            // 导致「更新应用」永远提示版本获取失败。必须把 withContext 的结果接出来再返回。
            val result: ReleaseResponse? = withContext(Dispatchers.IO) {
                try {
                    val request = okhttp3.Request.Builder().url(u).build()
                    val response = HttpClient.okHttpClient.newCall(request).execute()

                    if (response.isSuccessful) {
                        val body = response.bodyAlias()
                        if (body == null) {
                            Log.e(TAG, "getRelease $u body null")
                            null
                        } else {
                            val text = body.string()
                            Log.i(TAG, "getRelease body ${text.length} $text")
                            parseRelease(text)
                        }
                    } else {
                        Log.e(TAG, "getRelease $u ${response.codeAlias()}")
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "getRelease $u error $e")
                    null
                }
            }

            if (result != null) return result
        }

        return null
    }

    // 用 JSONObject 直接解析：不依赖反射/gson，避免 R8 混淆后字段名对不上导致永远拿不到版本号
    private fun parseRelease(text: String): ReleaseResponse? {
        return try {
            val obj = JSONObject(text)
            ReleaseResponse(
                if (obj.isNull("version_code")) null else obj.getInt("version_code"),
                if (obj.isNull("version_name")) null else obj.getString("version_name"),
                if (obj.isNull("apk_name")) null else obj.getString("apk_name"),
                if (obj.isNull("apk_url")) null else obj.getString("apk_url")
            )
        } catch (e: Exception) {
            Log.e(TAG, "parseRelease error $e")
            null
        }
    }

    fun checkAndUpdate() {
        Log.i(TAG, "checkAndUpdate")
        CoroutineScope(Dispatchers.Main).launch {
            var text = "版本获取失败"
            var update = false
            try {
                release = getRelease()
                Log.i(TAG, "versionCode $versionCode ${release?.version_code} ${release?.version_name}")
                if (release?.version_code != null) {
                    if (release?.version_code!! > versionCode) {
                        text = "最新版本：${release?.version_name}"
                        update = true
                    } else {
                        text = "已是最新版本，不需要更新"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error occurred: ${e.message}", e)
            }
            updateUI(text, update)
        }
    }

    private fun updateUI(text: String, update: Boolean) {
        val dialog = ConfirmationFragment(this@UpdateManager, text, update)
        dialog.show((context as FragmentActivity).supportFragmentManager, TAG)
    }

    private fun startDownload(release: ReleaseResponse) {
        if (release.apk_name.isNullOrEmpty() || release.apk_url.isNullOrEmpty()) {
            return
        }

        val downloadManager =
            context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request =
            Request(Uri.parse(release.apk_url))
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.mkdirs()
        Log.i(TAG, "save dir ${Environment.DIRECTORY_DOWNLOADS}")
        request.setDestinationInExternalFilesDir(
            context,
            Environment.DIRECTORY_DOWNLOADS,
            release.apk_name
        )
        request.setTitle("${context.getString(R.string.app_name)} ${release.version_name}")
        request.setNotificationVisibility(Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setAllowedOverRoaming(false)
        request.setMimeType("application/vnd.android.package-archive")

        // 获取下载任务的引用
        val downloadReference = downloadManager.enqueue(request)

        downloadReceiver = DownloadReceiver(context, release.apk_name, downloadReference)

        val intentFilter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(downloadReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(downloadReceiver, intentFilter)
        }

        getDownloadProgress(context, downloadReference) { progress ->
            println("Download progress: $progress%")
        }
    }

    private fun getDownloadProgress(
        context: Context,
        downloadId: Long,
        progressListener: (Int) -> Unit
    ) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val handler = Handler(Looper.getMainLooper())
        val intervalMillis: Long = 1000

        handler.post(object : Runnable {
            override fun run() {
                Log.i(TAG, "search")
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor: Cursor = downloadManager.query(query)
                cursor.use {
                    if (it.moveToFirst()) {
                        val bytesDownloadedIndex =
                            it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val bytesTotalIndex =
                            it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                        // 检查列名是否存在
                        if (bytesDownloadedIndex != -1 && bytesTotalIndex != -1) {
                            val bytesDownloaded = it.getInt(bytesDownloadedIndex)
                            val bytesTotal = it.getInt(bytesTotalIndex)

                            if (bytesTotal != -1) {
                                val progress = (bytesDownloaded * 100L / bytesTotal).toInt()
                                progressListener(progress)
                                if (progress == 100) {
                                    return
                                }
                            }
                        }
                    }
                }

//                handler.postDelayed(this, intervalMillis)
            }
        })
    }

    private class DownloadReceiver(
        private val context: Context,
        private val apkFileName: String,
        private val downloadReference: Long
    ) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val reference = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            Log.i(TAG, "reference $reference")

            if (reference == downloadReference) {
                val downloadManager =
                    context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(downloadReference)
                val cursor = downloadManager.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (statusIndex < 0) {
                        Log.i(TAG, "Download failure")
                        return
                    }
                    val status = cursor.getInt(statusIndex)

                    val progressIndex =
                        cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    if (progressIndex < 0) {
                        Log.i(TAG, "Download failure")
                        return
                    }
                    val progress = cursor.getInt(progressIndex)

                    val totalSizeIndex =
                        cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val totalSize = cursor.getInt(totalSizeIndex)

                    cursor.close()

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            installNewVersion()
                        }

                        DownloadManager.STATUS_FAILED -> {
                            // Handle download failure
                            Log.i(TAG, "Download failure")
                        }

                        else -> {
                            // Update UI with download progress
                            val percentage = progress * 100 / totalSize
                            Log.i(TAG, "Download progress: $percentage%")
                        }
                    }
                }
            }
        }

        private fun installNewVersion() {
            val apkFile = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                apkFileName
            )
            Log.i(TAG, "apkFile $apkFile")

            if (apkFile.exists()) {
                // Android 7+ 用 file:// 传 APK 会抛 FileUriExposedException 直接崩溃，必须用 FileProvider
                val apkUri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.FileProvider",
                    apkFile
                )
                Log.i(TAG, "apkUri $apkUri")
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                context.startActivity(installIntent)
            } else {
                Log.e(TAG, "APK file does not exist!")
            }
        }
    }

    companion object {
        private const val TAG = "UpdateManager"
        private const val BUFFER_SIZE = 8192
        // 电视在国内访问 raw.githubusercontent.com 基本不通，优先走 jsDelivr 的 GitHub 加速，
        // 再回退到 GitHub 原始地址。
        private val VERSION_URLS = listOf(
            "https://cdn.jsdelivr.net/gh/yyefree/my-tv-0-lite@main/version.json",
            "https://raw.githubusercontent.com/yyefree/my-tv-0-lite/main/version.json"
        )
    }

    override fun onConfirm() {
        Log.i(TAG, "onConfirm $release")
        release?.let { startDownload(it) }
    }

    override fun onCancel() {
    }

    fun destroy() {
        if (downloadReceiver != null) {
            context.unregisterReceiver(downloadReceiver)
            Log.i(TAG, "destroy downloadReceiver")
        }
    }
}