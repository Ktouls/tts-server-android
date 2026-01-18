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
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 严谨版 V3 引擎：修复 FileUtils 编译错误，锁定内部文件目录
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

    // 严谨的桥接接口
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
        val quickJs = QuickJs.create()
        val deferred = CompletableDeferred<Any?>()

        try {
            // 1. 注入 nativeBridge，使用标准 File API 确保编译通过
            quickJs.set("nativeBridge", JsBridge::class.java, object : JsBridge {
                override fun onSuccess(result: Any?) { deferred.complete(result) }
                override fun onError(error: String) { deferred.completeExceptionally(RuntimeException(error)) }
                
                override fun fetch(url: String, options: String): String {
                    return try {
                        val reqBuilder = Request.Builder().url(url).header("User-Agent", DEFAULT_UA)
                        if (options.contains("POST")) {
                            val body = Regex(""""body"\s*:\s*"(.*?)"""").find(options)?.groupValues?.get(1) ?: ""
                            reqBuilder.post(okhttp3.RequestBody.create(null, body))
                        }
                        client.newCall(reqBuilder.build()).execute().body?.string() ?: ""
                    } catch (e: Exception) { "ERROR: ${e.message}" }
                }

                // 使用 context.filesDir 确保路径安全性与 ES5 引擎一致
                override fun fileExist(path: String): Boolean = File(context.filesDir, path).exists()
                override fun readTxtFile(path: String): String = File(context.filesDir, path).let { 
                    if (it.exists()) it.readText() else "" 
                }
                override fun writeTxtFile(path: String, content: String) { 
                    File(context.filesDir, path).writeText(content) 
                }
                
                override fun getTtsData(key: String): String = plugin.userVars[key] ?: ""
            })

            // 2. 环境初始化 (注入 ttsrv 模拟对象)
            val bvValue = plugin.userVars["bv"] ?: ""
            val ttsDataJson = "{\"bv\": \"$bvValue\"}"
            
            quickJs.evaluate("""
                const console = { 
                    log: (m) => java.lang.System.out.println("[V3] " + m),
                    error: (m) => java.lang.System.err.println("[V3] " + m)
                };
                
                const ttsrv = {
                    fileExist: (p) => nativeBridge.fileExist(p),
                    readTxtFile: (p) => nativeBridge.readTxtFile(p),
                    writeTxtFile: (p, c) => nativeBridge.writeTxtFile(p, c),
                    tts: { data: $ttsDataJson }
                };

                const fetch = async (url, opt = {}) => {
                    const res = nativeBridge.fetch(url, JSON.stringify(opt));
                    if (res.startsWith("ERROR:")) throw new Error(res);
                    return { text: async () => res, json: async () => JSON.parse(res) };
                };
            """.trimIndent())

            // 3. 执行插件代码
            quickJs.evaluate(plugin.code, "plugin.js")

            // 4. 调用异步逻辑
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

            val result = withTimeout(35000L) { deferred.await() }
            return@withContext handleResult(result)

        } catch (e: Exception) {
            Log.e(TAG, "QuickJS 执行失败: ${e.message}")
            throw e
        } finally {
            quickJs.close()
        }
    }

    private fun handleResult(result: Any?): InputStream? {
        if (result == null) return null
        val data = result.toString().trim()
        
        if (data.startsWith("http")) {
            val resp = client.newCall(Request.Builder().url(data).header("User-Agent", DEFAULT_UA).build()).execute()
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} 下载失败")
            return resp.body?.byteStream()
        }

        return try {
            val audioBytes = Base64.decode(data, Base64.DEFAULT)
            ByteArrayInputStream(audioBytes)
        } catch (e: Exception) {
            throw Exception("返回数据格式错误 (非URL且非Base64): ${data.take(100)}")
        }
    }
}
