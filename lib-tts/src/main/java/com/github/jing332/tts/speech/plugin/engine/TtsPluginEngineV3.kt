package com.github.jing332.tts.speech.plugin.engine

import android.content.Context
import android.util.Base64
import android.util.Log
import app.cash.quickjs.QuickJs
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.script.runtime.console.Console
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

open class TtsPluginEngineV3(
    val context: Context, 
    var plugin: Plugin,
    protected val requestTimeout: Long,
    protected val console: Console = Console()
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
        fun log(msg: String)
        fun error(msg: String)
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
        val quickJs = try { QuickJs.create() } catch (e: Exception) { 
            console.error("V3 初始化失败: ${e.message}")
            throw e 
        }
        val deferred = CompletableDeferred<Any?>()

        try {
            quickJs.set("nativeBridge", JsBridge::class.java, object : JsBridge {
                override fun onSuccess(result: Any?) { deferred.complete(result) }
                override fun onError(error: String) { deferred.completeExceptionally(RuntimeException(error)) }
                override fun log(msg: String) { console.info("[JS] $msg") }
                override fun error(msg: String) { console.error("[JS] $msg") }

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
                        val resp = client.newCall(reqBuilder.build()).execute()
                        resp.body?.string() ?: ""
                    } catch (e: Exception) { "ERROR: ${e.message}" }
                }

                override fun fileExist(path: String): Boolean = File(context.filesDir, path).exists()
                override fun readTxtFile(path: String): String = File(context.filesDir, path).run { if (exists()) readText() else "" }
                override fun writeTxtFile(path: String, content: String) { File(context.filesDir, path).writeText(content) }
                override fun getTtsData(key: String): String = plugin.userVars[key] ?: ""
            })

            val bvValue = (plugin.userVars["bv"] ?: "").replace("\"", "\\\"")
            
            // 🛠️ 关键修复：使用 var 定义 fetch，确保它在全局作用域生效
            quickJs.evaluate("""
                var console = {
                    log: function(m) { nativeBridge.log(String(m)); },
                    error: function(m) { nativeBridge.error(String(m)); }
                };
                
                var ttsrv = {
                    fileExist: function(p) { return nativeBridge.fileExist(p); },
                    readTxtFile: function(p) { return nativeBridge.readTxtFile(p); },
                    writeTxtFile: function(p, c) { nativeBridge.writeTxtFile(p, c); },
                    tts: { data: {"bv": "$bvValue"} }
                };

                var Buffer = { from: function(data, type) { return data; } };

                var http = {
                    post: function(url, body, headers) {
                        var bodyStr = (typeof body === 'object') ? JSON.stringify(body) : String(body);
                        var resStr = nativeBridge.fetch(url, JSON.stringify({method: 'POST', body: bodyStr, headers: headers}));
                        if (resStr.startsWith("ERROR:")) throw new Error(resStr);
                        return {
                            json: function() { return JSON.parse(resStr); },
                            body: function() { return { string: function() { return resStr; } }; }
                        };
                    },
                    get: function(url, headers) {
                        var resStr = nativeBridge.fetch(url, JSON.stringify({method: 'GET', headers: headers}));
                        if (resStr.startsWith("ERROR:")) throw new Error(resStr);
                        return {
                            json: function() { return JSON.parse(resStr); },
                            body: function() { return { string: function() { return resStr; } }; }
                        };
                    }
                };

                // 使用 var 确保 fetch 也是全局的
                var fetch = async function(url, opt) {
                    if (!opt) opt = {};
                    var res = nativeBridge.fetch(url, JSON.stringify(opt));
                    if (res.startsWith("ERROR:")) throw new Error(res);
                    return { 
                        text: async function() { return res; }, 
                        json: async function() { return JSON.parse(res); } 
                    };
                };
            """.trimIndent())

            quickJs.evaluate(plugin.code, "plugin.js")

            val r = (rate * 50f).toInt(); val v = (volume * 50f).toInt(); val p = (pitch * 50f).toInt()
            quickJs.evaluate("""
                (async () => {
                    try {
                        const res = $OBJ_PLUGIN_JS.$FUNC_GET_AUDIO("$text", "$locale", "$voice", $r, $v, $p);
                        nativeBridge.onSuccess(res instanceof Promise ? await res : res);
                    } catch (e) { 
                        console.error("JS Error: " + e.message);
                        nativeBridge.onError(e.message); 
                    }
                })();
            """.trimIndent())

            val result = withTimeout(configTimeoutMs + 2000L) { deferred.await() }
            return@withContext handleResult(result)

        } catch (e: Exception) {
            console.error("V3 Exec Error: ${e.message}")
            e.printStackTrace()
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
        } catch (e: Exception) { null }
    }
}
