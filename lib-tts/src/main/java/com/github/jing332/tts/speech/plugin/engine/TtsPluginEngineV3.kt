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
 * 严谨版 V3：包含 Legacy 兼容层 (Polyfill)
 */
open class TtsPluginEngineV3(
    val context: Context, 
    var plugin: Plugin,
    protected val requestTimeout: Long 
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
        fun fetch(url: String, options: String): String // 这是一个同步方法
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
                
                // 核心：fetch 实现，同步执行请求并返回 String
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
            
            // 🛡️ 注入兼容层 (Polyfill)
            // 1. 模拟 console
            // 2. 模拟 ttsrv
            // 3. 模拟 http (重要！火山插件用了 http.post)
            // 4. 模拟 Buffer (重要！火山插件用了 Buffer.from)
            quickJs.evaluate("""
                const console = { log: (m) => java.lang.System.out.println("[V3] " + m), error: (m) => java.lang.System.err.println("[V3] " + m) };
                
                const ttsrv = {
                    fileExist: (p) => nativeBridge.fileExist(p),
                    readTxtFile: (p) => nativeBridge.readTxtFile(p),
                    writeTxtFile: (p, c) => nativeBridge.writeTxtFile(p, c),
                    tts: { data: {"bv": "$bvValue"} }
                };

                // 模拟 Buffer: 只要原样返回 base64 字符串即可，Kotlin 层会处理
                const Buffer = {
                    from: (data, type) => data 
                };

                // 模拟 http 对象 (适配旧插件)
                const http = {
                    post: (url, body, headers) => {
                        const resStr = nativeBridge.fetch(url, JSON.stringify({method: 'POST', body: body, headers: headers}));
                        if (resStr.startsWith("ERROR:")) throw new Error(resStr);
                        return {
                            json: () => JSON.parse(resStr),
                            body: () => ({ string: () => resStr })
                        };
                    },
                    get: (url, headers) => {
                        const resStr = nativeBridge.fetch(url, JSON.stringify({method: 'GET', headers: headers}));
                        if (resStr.startsWith("ERROR:")) throw new Error(resStr);
                        return {
                            json: () => JSON.parse(resStr),
                            body: () => ({ string: () => resStr })
                        };
                    }
                };

                // 模拟 fetch (适配新插件)
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
                        // 如果 res 是 Promise 则 await，否则直接使用
                        nativeBridge.onSuccess(res instanceof Promise ? await res : res);
                    } catch (e) { nativeBridge.onError(e.message); }
                })();
            """.trimIndent())

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
        // 移除可能存在的空白符
        val data = result.toString().replace(Regex("[\\s\\r\\n]"), "")
        if (data.startsWith("http")) {
            return try { client.newCall(Request.Builder().url(data).build()).execute().body?.byteStream() } catch (e: Exception) { null }
        }
        return try { 
            // 这里的 data 应该是 base64 字符串 (因为 Buffer.from 被我们 mock 成了直接返回字符串)
            ByteArrayInputStream(Base64.decode(data, Base64.DEFAULT)) 
        } catch (e: Exception) { 
            Log.e(TAG, "Base64 解码错误: ${e.message}")
            null 
        }
    }
}
