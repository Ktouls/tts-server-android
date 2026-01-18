package com.github.jing332.tts.speech.plugin.engine

import android.content.Context
import android.util.Base64
import android.util.Log
import app.cash.quickjs.QuickJs
import com.github.jing332.database.entities.plugin.Plugin
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 生产级 V3 引擎：支持 URL 自动下载与 Base64 自动解码
 */
open class TtsPluginEngineV3(val context: Context, var plugin: Plugin) {
    companion object {
        const val TAG = "TtsPluginEngineV3"
        const val OBJ_PLUGIN_JS = "PluginJS"
        const val FUNC_GET_AUDIO = "getAudio"
        const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    interface JsBridge {
        fun onSuccess(result: Any?)
        fun onError(error: String)
        fun fetch(url: String, options: String): String
    }

    suspend fun getAudio(
        text: String, locale: String, voice: String,
        rate: Float = 1f, volume: Float = 1f, pitch: Float = 1f
    ): InputStream? = withContext(Dispatchers.IO) {
        val quickJs = QuickJs.create()
        val deferred = CompletableDeferred<Any?>()

        try {
            quickJs.set("nativeBridge", JsBridge::class.java, object : JsBridge {
                override fun onSuccess(result: Any?) { deferred.complete(result) }
                override fun onError(error: String) { deferred.completeExceptionally(RuntimeException(error)) }
                override fun fetch(url: String, options: String): String {
                    return try {
                        // 简易 Fetch 实现，支持自定义方法和 Body
                        val reqBuilder = Request.Builder().url(url).header("User-Agent", DEFAULT_UA)
                        if (options.contains("POST")) {
                            val body = Regex(""""body"\s*:\s*"(.*?)"""").find(options)?.groupValues?.get(1) ?: ""
                            reqBuilder.post(okhttp3.RequestBody.create(null, body))
                        }
                        client.newCall(reqBuilder.build()).execute().body?.string() ?: ""
                    } catch (e: Exception) { "ERROR: ${e.message}" }
                }
            })

            // 初始化 ES6 环境
            quickJs.evaluate("""
                const console = { log: (m) => java.lang.System.out.println("[V3] " + m) };
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
                        const finalRes = (res instanceof Promise) ? await res : res;
                        nativeBridge.onSuccess(finalRes);
                    } catch (e) { nativeBridge.onError(e.message); }
                })();
            """.trimIndent())

            val result = withTimeout(30000L) { deferred.await() }
            return@withContext handleResult(result)

        } catch (e: Exception) {
            Log.e(TAG, "QuickJS 执行失败: ${e.message}")
            throw e
        } finally {
            quickJs.close()
        }
    }

    /**
     * 🛠️ 严谨的结果处理器：自动识别 URL 或 Base64
     */
    private fun handleResult(result: Any?): InputStream? {
        if (result == null) return null
        val data = result.toString().trim()

        // 1. 判定是否为 URL
        if (data.startsWith("http")) {
            val resp = client.newCall(Request.Builder().url(data).header("User-Agent", DEFAULT_UA).build()).execute()
            if (!resp.isSuccessful) throw Exception("URL下载失败: ${resp.code}")
            return resp.body?.byteStream()
        }

        // 2. 判定是否为 Base64 (尝试解码)
        return try {
            val audioBytes = Base64.decode(data, Base64.DEFAULT)
            ByteArrayInputStream(audioBytes)
        } catch (e: Exception) {
            // 3. 既不是 URL 也不是合法 Base64，抛出原始内容前缀供调试
            throw Exception("未知音频格式，内容前缀: ${data.take(100)}")
        }
    }
}
