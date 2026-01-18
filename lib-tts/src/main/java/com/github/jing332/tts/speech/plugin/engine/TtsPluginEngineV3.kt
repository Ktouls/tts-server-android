package com.github.jing332.tts.speech.plugin.engine

import android.content.Context
import android.util.Base64
import android.util.Log
import app.cash.quickjs.QuickJs
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 工业级 V3 引擎：完整注入 ttsrv 环境，支持文件操作、变量访问、Async/Await 及多种返回格式
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

    // 严谨的桥接接口，映射 ttsrv 的核心功能
    interface JsBridge {
        fun onSuccess(result: Any?)
        fun onError(error: String)
        fun fetch(url: String, options: String): String
        // ttsrv 核心文件操作
        fun fileExist(path: String): Boolean
        fun readTxtFile(path: String): String
        fun writeTxtFile(path: String, content: String)
        fun toast(msg: String)
        fun getTtsData(key: String): String
    }

    suspend fun getAudio(
        text: String, locale: String, voice: String,
        rate: Float = 1f, volume: Float = 1f, pitch: Float = 1f
    ): InputStream? = withContext(Dispatchers.IO) {
        val quickJs = QuickJs.create()
        val deferred = CompletableDeferred<Any?>()
        val ttsSource = PluginTtsSource() // 获取当前的 TTS 配置数据

        try {
            // 1. 注入 nativeBridge
            quickJs.set("nativeBridge", JsBridge::class.java, object : JsBridge {
                override fun onSuccess(result: Any?) { deferred.complete(result) }
                override fun onError(error: String) { deferred.completeExceptionally(RuntimeException(error)) }
                override fun fetch(url: String, options: String): String {
                    return try {
                        val reqBuilder = Request.Builder().url(url).header("User-Agent", DEFAULT_UA)
                        if (options.contains("POST")) {
                            // 简易 Body 提取
                            val body = Regex(""""body"\s*:\s*"(.*?)"""").find(options)?.groupValues?.get(1) ?: ""
                            reqBuilder.post(okhttp3.RequestBody.create(null, body))
                        }
                        client.newCall(reqBuilder.build()).execute().body?.string() ?: ""
                    } catch (e: Exception) { "ERROR: ${e.message}" }
                }
                override fun fileExist(path: String): Boolean = com.github.jing332.common.utils.FileUtils.exists(context, path)
                override fun readTxtFile(path: String): String = com.github.jing332.common.utils.FileUtils.readText(context, path)
                override fun writeTxtFile(path: String, content: String) { com.github.jing332.common.utils.FileUtils.saveText(context, path, content) }
                override fun toast(msg: String) { /* 宿主环境 Toast 逻辑 */ }
                override fun getTtsData(key: String): String = plugin.userVars[key] ?: ""
            })

            // 2. 在 JS 中模拟 ttsrv 对象结构
            val ttsDataJson = plugin.userVars.filterKeys { it == "bv" }.let { "{\"bv\": \"${it["bv"] ?: ""}\"}" }
            
            quickJs.evaluate("""
                const console = { log: (m) => java.lang.System.out.println("[V3] " + m) };
                
                // 🛠️ 关键注入：构造模拟的 ttsrv 对象
                const ttsrv = {
                    fileExist: (p) => nativeBridge.fileExist(p),
                    readTxtFile: (p) => nativeBridge.readTxtFile(p),
                    writeTxtFile: (p, c) => nativeBridge.writeTxtFile(p, c),
                    toast: (m) => nativeBridge.toast(m),
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

            // 4. 执行异步调用
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

    private fun handleResult(result: Any?): InputStream? {
        if (result == null) return null
        val data = result.toString().trim()
        if (data.startsWith("http")) {
            val resp = client.newCall(Request.Builder().url(data).header("User-Agent", DEFAULT_UA).build()).execute()
            return if (resp.isSuccessful) resp.body?.byteStream() else throw Exception("Download Error: ${resp.code}")
        }
        return try {
            ByteArrayInputStream(Base64.decode(data, Base64.DEFAULT))
        } catch (e: Exception) {
            throw Exception("Invalid Result Format: ${data.take(100)}")
        }
    }
}
