package com.github.jing332.tts.speech.plugin.engine

import android.content.Context
import android.util.Base64
import android.util.Log
import app.cash.quickjs.QuickJs
import com.github.jing332.database.entities.plugin.Plugin
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 严谨版 V3：注入式超时控制与 Base64 强力清洗
 */
open class TtsPluginEngineV3(
    val context: Context, 
    var plugin: Plugin,
    protected val requestTimeout: Long // 🛡️ 注入用户配置
) {
    companion object {
        const val TAG = "TtsPluginEngineV3"
        const val OBJ_PLUGIN_JS = "PluginJS"
        const val FUNC_GET_AUDIO = "getAudio"
        const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private val configTimeoutMs: Long
        get() = requestTimeout.coerceAtLeast(5000L)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(configTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(configTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(configTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    interface JsBridge {
        fun onSuccess(result: Any?)
        fun onError(error: String)
        fun fetch(url: String, options: String): String
        fun fileExist(path: String): Boolean
        fun readTxtFile(path: String): String
        fun writeTxtFile(path: String, content: String)
        fun getTtsData(key: String): String
    }

    suspend fun getAudio(
        text: String, locale: String, voice: String,
        rate: Float = 1f, volume: Float = 1f, pitch: Float = 1f
    ): InputStream? = withContext(Dispatchers.IO) {
        val quickJs = try { QuickJs.create() } catch (e: Exception) { throw e }
        val deferred = CompletableDeferred<Any?>()

        try {
            quickJs.set("nativeBridge", JsBridge::class.java, object : JsBridge {
                override fun onSuccess(result: Any?) { deferred.complete(result) }
                override fun onError(error: String) { deferred.completeExceptionally(RuntimeException(error)) }
                
                override fun fetch(url: String, options: String): String {
                    return try {
                        val opt = JSONObject(options)
                        val method = opt.optString("method", "GET")
                        val reqBuilder = Request.Builder().url(url)
                        
                        opt.optJSONObject("headers")?.let { headers ->
                            headers.keys().forEach { key -> reqBuilder.header(key, headers.getString(key)) }
                        }
                        if (reqBuilder.build().header("User-Agent") == null) reqBuilder.header("User-Agent", DEFAULT_UA)

                        if (method.uppercase() == "POST") {
                            val bodyStr = opt.optString("body", "")
                            val contentType = opt.optJSONObject("headers")?.optString("Content-Type") ?: "application/json"
                            reqBuilder.post(bodyStr.toRequestBody(contentType.toMediaTypeOrNull()))
                        }
                        client.newCall(reqBuilder.build()).execute().body?.string() ?: ""
                    } catch (e: Exception) { "ERROR: ${e.message}" }
                }

                override fun fileExist(path: String): Boolean = File(context.filesDir, path).exists()
                override fun readTxtFile(path: String): String = File(context.filesDir, path).run { if (exists()) readText() else "" }
                override fun writeTxtFile(path: String, content: String) { File(context.filesDir, path).writeText(content) }
                override fun getTtsData(key: String): String = plugin.userVars[key] ?: ""
            })

            val bvValue = (plugin.userVars["bv"] ?: "").replace("\"", "\\\"")
            quickJs.evaluate("""
                const console = { log: (m) => java.lang.System.out.println("[V3] " + m), error: (m) => java.lang.System.err.println("[V3] " + m) };
                const ttsrv = {
                    fileExist: (p) => nativeBridge.fileExist(p),
                    readTxtFile: (p) => nativeBridge.readTxtFile(p),
                    writeTxtFile: (p, c) => nativeBridge.writeTxtFile(p, c),
                    tts: { data: {"bv": "$bvValue"} }
                };
                const fetch = async (url, opt = {}) => {
                    const res = nativeBridge.fetch(url, JSON.stringify(opt));
                    if (res.startsWith("ERROR:")) throw new Error(res);
                    return { text: async () => res, json: async () => JSON.parse(res) };
                };
            """.trimIndent())

            quickJs.evaluate(plugin.code, "plugin.js")

            val r = (rate * 50f).toInt(); val v = (volume * 50f).toInt(); val p = (pitch * 50f).toInt()
            quickJs.evaluate("""
                (async () => {
                    try {
                        const res = $OBJ_PLUGIN_JS.$FUNC_GET_AUDIO("$text", "$locale", "$voice", $r, $v, $p);
                        nativeBridge.onSuccess(await res);
                    } catch (e) { nativeBridge.onError(e.message); }
                })();
            """.trimIndent())

            // 🛡️ 严谨：配置时间 + 2秒冗余缓冲
            val result = withTimeout(configTimeoutMs + 2000L) { deferred.await() }
            return@withContext handleResult(result)

        } catch (e: Exception) {
            Log.e(TAG, "V3 引擎执行失败: ${e.message}")
            null
        } finally {
            quickJs.close()
        }
    }

    private fun handleResult(result: Any?): InputStream? {
        if (result == null) return null
        val data = result.toString().replace(Regex("[\\s\\r\\n]"), "")
        if (data.startsWith("http")) {
            return try { client.newCall(Request.Builder().url(data).build()).execute().body?.byteStream() } catch (e: Exception) { null }
        }
        return try { 
            ByteArrayInputStream(Base64.decode(data, Base64.DEFAULT)) 
        } catch (e: Exception) { 
            Log.e(TAG, "Base64 解码致命错误: ${e.message}")
            null 
        }
    }
}
