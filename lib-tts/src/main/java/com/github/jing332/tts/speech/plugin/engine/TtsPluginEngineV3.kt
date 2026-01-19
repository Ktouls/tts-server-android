package com.github.jing332.tts.speech.plugin.engine

import android.content.Context
import android.util.Base64
import android.util.Log
import app.cash.quickjs.QuickJs
import com.github.jing332.database.entities.plugin.Plugin
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 加固版 V3 引擎：增加 Fetch 状态追踪与异步安全回调
 */
open class TtsPluginEngineV3(val context: Context, var plugin: Plugin) {
    companion object {
        const val TAG = "TtsPluginEngineV3"
        const val OBJ_PLUGIN_JS = "PluginJS"
        const val FUNC_GET_AUDIO = "getAudio"
        const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

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
        // 🛠️ 严谨：每次请求创建独立引擎，确保并发安全，用完立即 close
        val quickJs = try { QuickJs.create() } catch (e: Exception) { 
            Log.e(TAG, "QuickJS 实例创建失败"); throw e 
        }
        val deferred = CompletableDeferred<Any?>()

        try {
            quickJs.set("nativeBridge", JsBridge::class.java, object : JsBridge {
                override fun onSuccess(result: Any?) { 
                    Log.d(TAG, "JS 回调成功"); deferred.complete(result) 
                }
                override fun onError(error: String) { 
                    Log.e(TAG, "JS 回调失败: $error"); deferred.completeExceptionally(RuntimeException(error)) 
                }
                
                override fun fetch(url: String, options: String): String {
                    Log.d(TAG, "开始网络请求: $url")
                    return try {
                        val reqBuilder = Request.Builder().url(url).header("User-Agent", DEFAULT_UA)
                        if (options.contains("POST")) {
                            val body = Regex(""""body"\s*:\s*"(.*?)"""").find(options)?.groupValues?.get(1) ?: ""
                            reqBuilder.post(body.toRequestBody(null))
                        }
                        val response = client.newCall(reqBuilder.build()).execute()
                        val resBody = response.body?.string() ?: ""
                        Log.d(TAG, "请求响应完成，长度: ${resBody.length}")
                        resBody
                    } catch (e: Exception) { 
                        Log.e(TAG, "Fetch 网络错误: ${e.message}"); "ERROR: ${e.message}" 
                    }
                }

                override fun fileExist(path: String): Boolean = File(context.filesDir, path).exists()
                override fun readTxtFile(path: String): String = File(context.filesDir, path).run { if (exists()) readText() else "" }
                override fun writeTxtFile(path: String, content: String) { File(context.filesDir, path).writeText(content) }
                override fun getTtsData(key: String): String = plugin.userVars[key] ?: ""
            })

            // 初始化环境
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
                        if (typeof $OBJ_PLUGIN_JS === 'undefined') throw new Error("$OBJ_PLUGIN_JS is not defined");
                        const res = $OBJ_PLUGIN_JS.$FUNC_GET_AUDIO("$text", "$locale", "$voice", $r, $v, $p);
                        const finalRes = (res instanceof Promise) ? await res : res;
                        nativeBridge.onSuccess(finalRes);
                    } catch (e) {
                        nativeBridge.onError(e.message);
                    }
                })();
            """.trimIndent())

            // 🛠️ 严谨：将超时时间缩短为 25 秒，并在超时后强制 cancel deferred 防止 UI 挂起
            val result = withTimeout(25000L) { deferred.await() }
            return@withContext handleResult(result)

        } catch (e: Exception) {
            Log.e(TAG, "执行生命周期异常: ${e.message}")
            if (deferred.isActive) deferred.complete(null)
            throw e
        } finally {
            quickJs.close() // 🛠️ 强制释放 Native 资源
        }
    }

    private fun handleResult(result: Any?): InputStream? {
        if (result == null) return null
        val data = result.toString().trim().replace("\n", "").replace("\r", "")
        
        if (data.startsWith("http")) {
            val resp = client.newCall(Request.Builder().url(data).header("User-Agent", DEFAULT_UA).build()).execute()
            return if (resp.isSuccessful) resp.body?.byteStream() else null
        }

        return try {
            val audioBytes = Base64.decode(data, Base64.DEFAULT)
            ByteArrayInputStream(audioBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Base64 解码失败: ${e.message}"); null
        }
    }
}
