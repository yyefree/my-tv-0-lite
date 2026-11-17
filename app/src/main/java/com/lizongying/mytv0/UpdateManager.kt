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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

    // 后台自动升级：每小时静默探测一次，通了就静默下载，下完只在确认安装时打扰用户
    private var pendingInstallMode = false

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

    // ==================== 后台自动升级 ====================
    // 思路：每小时静默探测一次 GitHub 可达性；能拿到版本就后台静默下载 APK；
    // 下载完成后才提示用户"可以升级"，由用户确认后安装。

    fun startAutoPoll() {
        if (pollJob?.isActive == true) {
            Log.i(TAG, "startAutoPoll 已在运行，跳过")
            return
        }
        Log.i(
            TAG,
            "startAutoPoll 启动：首轮 ${INITIAL_DELAY_MS / 1000}s 后，之后每 ${POLL_INTERVAL_MS / 60000} 分钟一次"
        )
        pollJob = CoroutineScope(Dispatchers.Main).launch {
            delay(INITIAL_DELAY_MS)
            while (isActive) {
                try {
                    autoCheck()
                } catch (e: Exception) {
                    Log.e(TAG, "autoCheck error", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        // 上次进程被杀时可能已经下完，启动后补一次安装提示
        CoroutineScope(Dispatchers.Main).launch {
            delay(3000)
            if (hasPendingApk()) promptPendingInstall()
        }
    }

    fun stopAutoPoll() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun autoCheck() {
        SP.updateLastCheck = System.currentTimeMillis()
        Log.i(TAG, "autoCheck 开始静默探测")

        if (hasPendingApk()) {
            promptPendingInstall()
            return
        }

        val downloadId = SP.updateDownloadId
        if (downloadId > 0) {
            when (queryDownloadStatus(downloadId)) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    // 下载记录是成功但文件可能已被删除（换机、手动清理），必须核实文件还在
                    val file = File(
                        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                        SP.updatePendingApk
                    )
                    if (file.exists() && file.length() > 0) {
                        SP.updatePendingReady = true
                        Log.i(TAG, "autoCheck 上一轮后台下载已完成 (${file.length()} B)")
                        promptPendingInstall()
                        return
                    }
                    Log.i(TAG, "autoCheck 下载记录成功但文件已不存在，清理后重新探测")
                    clearPendingDownload()
                }

                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> {
                    Log.i(TAG, "autoCheck 下载仍在进行，本轮跳过")
                    return
                }

                else -> {
                    Log.i(TAG, "autoCheck 上一轮下载未成功，清理后重试")
                    clearPendingDownload()
                }
            }
        }

        val r = getRelease()
        if (r == null) {
            Log.i(TAG, "autoCheck 探测失败：当前网络拿不到版本信息，等下一轮")
            return
        }
        val remoteCode = r.version_code ?: return
        Log.i(TAG, "autoCheck 远端 ${r.version_name}($remoteCode) 本地 $versionCode")
        if (remoteCode <= versionCode) {
            Log.i(TAG, "autoCheck 已是最新版本")
            return
        }
        if (remoteCode.toLong() == SP.updateDismissedCode) {
            Log.i(TAG, "autoCheck 该版本已被用户忽略，不再提示")
            return
        }
        Log.i(TAG, "autoCheck 发现新版本，开始后台静默下载 ${r.apk_name}")
        startDownload(r)
    }

    private fun hasPendingApk(): Boolean {
        if (!SP.updatePendingReady) return false
        val file = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            SP.updatePendingApk
        )
        val exists = file.exists() && file.length() > 0
        Log.i(TAG, "hasPendingApk ${SP.updatePendingApk} -> $exists (${file.length()} B)")
        if (!exists) SP.updatePendingReady = false
        return exists
    }

    private fun queryDownloadStatus(id: Long): Int {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var status = -1
        try {
            downloadManager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (index >= 0) status = cursor.getInt(index)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryDownloadStatus error", e)
        }
        return status
    }

    private fun clearPendingDownload() {
        SP.updateDownloadId = -1L
        SP.updatePendingReady = false
    }

    private fun promptPendingInstall() {
        val activity = context as? FragmentActivity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        if (activity.supportFragmentManager.findFragmentByTag(TAG) is ConfirmationFragment) return
        Log.i(TAG, "promptPendingInstall 提示安装 ${SP.updatePendingName}")
        pendingInstallMode = true
        ConfirmationFragment(this@UpdateManager, "新版本 ${SP.updatePendingName} 已下载完成", true)
            .show(activity.supportFragmentManager, TAG)
    }

    private fun installPending() {
        val apkFile = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            SP.updatePendingApk
        )
        Log.i(TAG, "installPending apkFile $apkFile (${apkFile.length()} B)")
        if (!apkFile.exists()) {
            Log.e(TAG, "installPending APK 不存在")
            SP.updatePendingReady = false
            return
        }
        try {
            val apkUri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.FileProvider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            SP.updatePendingReady = false
            SP.updateDownloadId = -1L
        } catch (e: Exception) {
            Log.e(TAG, "installPending error", e)
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

        // 先把要装的版本记进 SP：即使进程中途被杀，下次启动也能靠 DownloadManager 查询恢复
        SP.updatePendingCode = release.version_code?.toLong() ?: 0L
        SP.updatePendingName = release.version_name ?: ""
        SP.updatePendingApk = release.apk_name
        SP.updatePendingReady = false

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
        SP.updateDownloadId = downloadReference
        Log.i(TAG, "startDownload enqueue id=$downloadReference ${release.apk_name}")

        downloadReceiver = DownloadReceiver(release.apk_name, downloadReference)

        val intentFilter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(downloadReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(downloadReceiver, intentFilter)
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

    private inner class DownloadReceiver(
        private val apkFileName: String,
        private val downloadReference: Long
    ) : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val reference = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            Log.i(TAG, "DownloadReceiver id=$reference")

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
                            val file = File(
                                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                                apkFileName
                            )
                            if (file.exists() && file.length() > 0) {
                                SP.updatePendingReady = true
                                Log.i(
                                    TAG,
                                    "下载完成 $apkFileName (${file.length()} B)，等待用户确认安装"
                                )
                                promptPendingInstall()
                            } else {
                                Log.e(TAG, "下载完成但文件不存在或为空")
                                clearPendingDownload()
                            }
                        }

                        DownloadManager.STATUS_FAILED -> {
                            Log.i(TAG, "下载失败，下一轮探测会重试")
                            clearPendingDownload()
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

    }

    companion object {
        private const val TAG = "UpdateManager"
        private const val BUFFER_SIZE = 8192

        // 保留 GitHub 作为主源（这台电视偶尔能连上），jsDelivr 作为加速兜底
        private val VERSION_URLS = listOf(
            "https://raw.githubusercontent.com/yyefree/my-tv-0-lite/main/version.json",
            "https://cdn.jsdelivr.net/gh/yyefree/my-tv-0-lite@main/version.json"
        )

        // 每 1 小时静默探测一次；App 启动后先等 60 秒，避免和首屏播放抢带宽
        private const val POLL_INTERVAL_MS = 60L * 60 * 1000
        private const val INITIAL_DELAY_MS = 60L * 1000

        // 轮询任务放静态，Activity 重建时不会重复起一份
        private var pollJob: Job? = null
    }

    override fun onConfirm() {
        if (pendingInstallMode) {
            pendingInstallMode = false
            installPending()
            return
        }
        Log.i(TAG, "onConfirm $release")
        release?.let { startDownload(it) }
    }

    override fun onCancel() {
        if (pendingInstallMode) {
            pendingInstallMode = false
            // 用户选了「稍后」，本轮不再自动弹这个版本；文件留着，手动点「更新应用」仍可装
            SP.updateDismissedCode = SP.updatePendingCode
            Log.i(TAG, "用户忽略本次安装提示：${SP.updatePendingName}")
        }
    }

    fun destroy() {
        if (downloadReceiver != null) {
            context.unregisterReceiver(downloadReceiver)
            Log.i(TAG, "destroy downloadReceiver")
        }
    }
}