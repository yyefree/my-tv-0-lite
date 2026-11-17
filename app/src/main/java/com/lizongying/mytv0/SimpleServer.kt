package com.lizongying.mytv0


import MainViewModel
import MainViewModel.Companion.CACHE_FILE_NAME
import MainViewModel.Companion.DEFAULT_CHANNELS_FILE
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lizongying.mytv0.Utils.getUrls
import com.lizongying.mytv0.data.Global.gson
import com.lizongying.mytv0.data.Global.typeSourceList
import com.lizongying.mytv0.data.ReqSettings
import com.lizongying.mytv0.data.ReqSourceAdd
import com.lizongying.mytv0.data.ReqSources
import com.lizongying.mytv0.data.RespSettings
import com.lizongying.mytv0.data.Source
import com.lizongying.mytv0.requests.HttpClient
import fi.iki.elonen.NanoHTTPD
import io.github.lizongying.Gua
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets


class SimpleServer(private val context: Context, private val viewModel: MainViewModel) :
    NanoHTTPD(PORT) {
    private val handler = Handler(Looper.getMainLooper())

    init {
        try {
            start()
        } catch (e: Exception) {
            Logger.e(TAG, "init", e)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        // 写接口鉴权：原来 /api/settings、/api/import-text、/api/proxy、/api/remove-source
        // 等八个接口谁都能调，同一个 WiFi 下的任何设备（包括手机上的网页）都能改你的源和代理。
        // 现在要求带 token：它只出现在设置面板二维码的地址里，页面 JS 会把它拼到后续请求上。
        if (session.uri.startsWith("/api") && !isAuthorized(session)) {
            Logger.w(TAG, "拒绝无 token 的请求 ${session.uri} from ${session.remoteIpAddress}")
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                MIME_PLAINTEXT,
                "forbidden: token required"
            )
        }

        return when (session.uri) {
            "/api/settings" -> handleSettings()
            "/api/sources" -> handleSources()
            "/api/import-text" -> handleImportText(session)
            "/api/import-uri" -> handleImportUri(session)
            "/api/proxy" -> handleProxy(session)
            "/api/epg" -> handleEPG(session)
            "/api/default-channel" -> handleDefaultChannel(session)
            "/api/remove-source" -> handleRemoveSource(session)
            else -> handleStaticContent()
        }
    }

    private fun handleSettings(): Response {
        val response: String
        try {
            val file = File(context.filesDir, CACHE_FILE_NAME)
            var str = if (file.exists()) {
                file.readText()
            } else {
                ""
            }
            if (str.isEmpty()) {
                str = context.resources.openRawResource(DEFAULT_CHANNELS_FILE).bufferedReader()
                    .use { it.readText() }
            }

            var history = mutableListOf<Source>()

            if (!SP.sources.isNullOrEmpty()) {
                try {
                    val sources: List<Source> = gson.fromJson(SP.sources!!, typeSourceList)
                    history = sources.toMutableList()
                } catch (e: Exception) {
                    e.printStackTrace()
                    SP.sources = SP.DEFAULT_SOURCES
                }
            }

            val respSettings = RespSettings(
                channelUri = SP.configUrl ?: "",
                channelText = str,
                channelDefault = SP.channel,
                proxy = SP.proxy ?: "",
                epg = SP.epg ?: "",
                history = history
            )
            response = gson.toJson(respSettings) ?: ""
        } catch (e: Exception) {
            Logger.e(TAG, "handleSettings", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", response)
    }

    private suspend fun fetchSources(url: String): String {
        val urls = getUrls(url)

        var sources = ""
        var success = false
        for (u in urls) {
            Logger.i(TAG, "request $u")
            withContext(Dispatchers.IO) {
                try {
                    val request = okhttp3.Request.Builder().url(u).build()
                    val response = HttpClient.okHttpClient.newCall(request).execute()

                    if (response.isSuccessful) {
                        sources = response.bodyAlias()?.string() ?: ""
                        success = true
                    } else {
                        Logger.e(TAG, "Request status ${response.codeAlias()}")
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "fetchSources", e)
                }
            }

            if (success) break
        }

        return sources
    }

    private fun handleSources(): Response {
        val response = runBlocking(Dispatchers.IO) {
            fetchSources("https://raw.githubusercontent.com/lizongying/my-tv-0/main/app/src/main/res/raw/sources.txt")
        }

        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            Gua().decode(response)
        )
    }

    private fun handleImportText(session: IHTTPSession): Response {
        R.string.start_config_channel.showToast()
        val response = ""
        try {
            readBody(session)?.let {
                handler.post {
                    viewModel.tryStr2Channels(it, null, "")
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "handleImportText", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun handleImportUri(session: IHTTPSession): Response {
        R.string.start_config_channel.showToast()
        val response = ""
        try {
            readBody(session)?.let {
                val req = gson.fromJson(it, ReqSourceAdd::class.java)
                val uri = Uri.parse(req.uri)
                handler.post {
                    viewModel.importFromUri(uri, req.id)
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "handleImportUri", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun handleProxy(session: IHTTPSession): Response {
        try {
            readBody(session)?.let {
                handler.post {
                    val req = gson.fromJson(it, ReqSettings::class.java)
                    if (req.proxy != null) {
                        SP.proxy = req.proxy
                        R.string.default_proxy_set_success.showToast()
                        Logger.i(TAG, "set proxy success")
                    } else {
                        R.string.default_proxy_set_failure.showToast()
                        Logger.i(TAG, "set proxy failure")
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "handleProxy", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        val response = ""
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun handleEPG(session: IHTTPSession): Response {
        try {
            readBody(session)?.let {
                handler.post {
                    val req = gson.fromJson(it, ReqSettings::class.java)
                    if (req.epg != null) {
                        SP.epg = req.epg
                        viewModel.updateEPG()
                        R.string.default_epg_set_success.showToast()
                    } else {
                        R.string.default_epg_set_failure.showToast()
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "handleEPG", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        val response = ""
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun handleDefaultChannel(session: IHTTPSession): Response {
        R.string.start_set_default_channel.showToast()
        val response = ""
        try {
            readBody(session)?.let {
                handler.post {
                    val req = gson.fromJson(it, ReqSettings::class.java)
                    if (req.channel != null && req.channel > -1) {
                        SP.channel = req.channel
                        R.string.default_channel_set_success.showToast()
                    } else {
                        R.string.default_channel_set_failure.showToast()
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "handleDefaultChannel", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun handleRemoveSource(session: IHTTPSession): Response {
        val response = ""
        try {
            readBody(session)?.let {
                handler.post {
                    val req = gson.fromJson(it, ReqSources::class.java)
                    Logger.i(TAG, "req $req")
                    if (req.sourceId.isNotEmpty()) {
                        val res = viewModel.sources.removeSource(req.sourceId)
                        if (res) {
                            Logger.i(TAG, "remove source success ${req.sourceId}")
                        } else {
                            Logger.i(TAG, "remove source failure ${req.sourceId}")
                        }
                    } else {
                        Logger.i(TAG, "remove source failure, sourceId is empty")
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "handleRemoveSource", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun readBody(session: IHTTPSession): String? {
        val map = HashMap<String, String>()
        session.parseBody(map)
        return map["postData"]
    }

    /** token 从 query 参数取（页面 JS 统一拼在 URL 上），不匹配就拒绝 */
    private fun isAuthorized(session: IHTTPSession): Boolean {
        val token = session.parms?.get("token") ?: return false
        return token == SP.serverToken
    }

    private fun handleStaticContent(): Response {
        val html = loadHtmlFromResource(R.raw.index)
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun loadHtmlFromResource(resourceId: Int): String {
        val inputStream = context.resources.openRawResource(resourceId)
        return inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    companion object {
        const val TAG = "SimpleServer"
        const val PORT = 34567
    }
}