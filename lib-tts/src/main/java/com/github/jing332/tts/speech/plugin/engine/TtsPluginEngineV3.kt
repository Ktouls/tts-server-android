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

/**
 * V3 引擎最终版：同步执行模式 (Synchronous Mode)
 * 解决 async/await 导致的 EventLoop 超时问题
 */
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
        fun log(msg: String)
        fun error(msg: String)
        fun fetch(url: String, options: String): String // 同步网络请求
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
        
        try {
            // 注册 Native 桥接
            quickJs.set("nativeBridge", JsBridge::class.java, object : JsBridge {
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
                        // 同步执行，阻塞直到返回
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
            
            // 🛠️ 注入同步环境 (Polyfill)
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

                // 同步 fetch 实现：不需要 await，直接返回结果对象
                var fetch = function(url, opt) {
                    if (!opt) opt = {};
                    var bodyStr = opt.body;
                    if (typeof bodyStr === 'object') bodyStr = JSON.stringify(bodyStr);
                    // 更新 options 里的 body
                    var newOpt = {
                        method: opt.method || 'GET',
                        headers: opt.headers || {},
                        body: bodyStr || ""
                    };
                    
                    var resStr = nativeBridge.fetch(url, JSON.stringify(newOpt));
                    
                    if (resStr.startsWith("ERROR:")) throw new Error(resStr);
                    
                    return {
                        text: function() { return resStr; },
                        json: function() { return JSON.parse(resStr); }
                    };
                };
                
                // 兼容旧版 http 对象
                var http = {
                    post: function(url, body, headers) {
                        return fetch(url, { method: 'POST', body: body, headers: headers });
                    },
                    get: function(url, headers) {
                        return fetch(url, { method: 'GET', headers: headers });
                    }
                };
                
                var Buffer = { from: function(d, t) { return d; } };
            """.trimIndent())

            // 加载用户插件代码
            quickJs.evaluate(plugin.code, "plugin.js")

            val r = (rate * 50f).toInt(); val v = (volume * 50f).toInt(); val p = (pitch * 50f).toInt()
            
            // 🛠️ 关键修改：直接执行并获取返回值，不使用 async/await，也不使用 callback
            // 这样会强制 QuickJS 同步等待结果，彻底解决超时问题
            val resultJs = quickJs.evaluate("""
                (function() {
                    try {
                        return $OBJ_PLUGIN_JS.$FUNC_GET_AUDIO("$text", "$locale", "$voice", $r, $v, $p);
                    } catch (e) {
                        return "ERROR:" + e.message;
                    }
                })();
            """.trimIndent())

            return@withContext handleResult(resultJs)

        } catch (e: Exception) {
            console.error("V3 执行异常: ${e.message}")
            e.printStackTrace()
            null
        } finally {
            quickJs.close()
        }
    }

    private fun handleResult(result: Any?): InputStream? {
        if (result == null) {
            console.error("V3 返回空值")
            return null
        }
        val data = result.toString().trim()
        
        if (data.startsWith("ERROR:")) {
            console.error("插件执行报错: ${data.removePrefix("ERROR:")}")
            return null
        }
        
        if (data.startsWith("http")) {
            return try { client.newCall(Request.Builder().url(data).build()).execute().body?.byteStream() } catch (e: Exception) { null }
        }
        
        return try { 
            ByteArrayInputStream(Base64.decode(data, Base64.DEFAULT)) 
        } catch (e: Exception) { 
            console.error("Base64解码失败，长度: ${data.length}")
            null 
        }
    }
}
