package com.github.jing332.tts.speech.plugin.engine

import android.content.Context
import android.util.Log
import app.cash.quickjs.QuickJs
import com.github.jing332.database.entities.plugin.Plugin
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 严谨版 V3 引擎 (支持 Async + 增强型下载器)
 */
open class TtsPluginEngineV3(val context: Context, var plugin: Plugin) {
    companion object {
        const val TAG = "TtsPluginEngineV3"
        const val OBJ_PLUGIN_JS = "PluginJS"
        const val FUNC_GET_AUDIO = "getAudio"
        // 统一 User-Agent 防止被服务器拦截
        const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    interface JsBridge {
        fun onSuccess(result: Any?)
        fun onError(error: String)
        fun fetch(url: String): String
    }

    suspend fun getAudio(
        text: String,
        locale: String,
        voice: String,
        rate: Float = 1f,
        volume: Float = 1f,
        pitch: Float = 1f
    ): InputStream? = withContext(Dispatchers.IO) {
        val quickJs = QuickJs.create()
        val deferred = CompletableDeferred<Any?>()

        try {
            // 1. 注入桥接
            quickJs.set("nativeBridge", JsBridge::class.java, object : JsBridge {
                override fun onSuccess(result: Any?) { deferred.complete(result) }
                override fun onError(error: String) { deferred.completeExceptionally(RuntimeException(error)) }
                override fun fetch(url: String): String {
                    return try {
                        val req = Request.Builder().url(url).header("User-Agent", DEFAULT_UA).build()
                        client.newCall(req).execute().body?.string() ?: ""
                    } catch (e: Exception) { "ERROR: ${e.message}" }
                }
            })

            // 2. 环境初始化
            quickJs.evaluate("""
                const console = { 
                    log: (m) => java.lang.System.out.println("[V3] " + m),
                    error: (m) => java.lang.System.err.println("[V3] " + m)
                };
                const fetch = async (url) => {
                    const res = nativeBridge.fetch(url);
                    if (res.startsWith("ERROR:")) throw new Error(res);
                    return { text: async () => res, json: async () => JSON.parse(res) };
                };
            """.trimIndent())

            // 3. 加载脚本
            quickJs.evaluate(plugin.code, "plugin.js")

            // 4. 调用 (支持同步和异步函数)
            val r = (rate * 50f).toInt()
            val v = (volume * 50f).toInt()
            val p = (pitch * 50f).toInt()

            val callScript = """
                (async () => {
                    try {
                        const res = $OBJ_PLUGIN_JS.$FUNC_GET_AUDIO("$text", "$locale", "$voice", $r, $v, $p);
                        // 自动识别并等待 Promise
                        const finalRes = (res instanceof Promise) ? await res : res;
                        nativeBridge.onSuccess(finalRes);
                    } catch (e) {
                        nativeBridge.onError(e.message);
                    }
                })();
            """.trimIndent()

            quickJs.evaluate(callScript)

            // 5. 等待结果
            val result = withTimeout(30000L) { deferred.await() }
            return@withContext downloadAudio(result)

        } catch (e: Exception) {
            Log.e(TAG, "QuickJS 执行失败: ${e.message}")
            throw e
        } finally {
            quickJs.close()
        }
    }

    private fun downloadAudio(result: Any?): InputStream? {
        if (result == null) return null
        val url = result.toString()
        if (!url.startsWith("http")) throw Exception("返回结果不是有效 URL: $url")

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_UA) // 注入 UA
            .build()

        val resp = client.newCall(req).execute()
        
        // 严谨校验：如果返回的不是音频流（比如返回了 text/html），直接报错
        val contentType = resp.header("Content-Type") ?: ""
        if (contentType.contains("text/html") || contentType.contains("application/json")) {
            val body = resp.body?.string() ?: ""
            throw Exception("服务器未返回音频流，可能被拦截。内容前缀: ${body.take(100)}")
        }

        if (!resp.isSuccessful) throw Exception("下载失败: HTTP ${resp.code}")
        return resp.body?.byteStream()
    }
}
