package com.github.jing332.tts.speech.plugin.engine

import android.content.Context
import android.util.Log
import app.cash.quickjs.QuickJs
import com.github.jing332.database.entities.plugin.Plugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 基于 CashApp QuickJS (Maven Central) 的 V3 引擎
 * 彻底解决构建下载问题
 */
open class TtsPluginEngineV3(val context: Context, var plugin: Plugin) {
    companion object {
        const val OBJ_PLUGIN_JS = "PluginJS"
        const val FUNC_GET_AUDIO = "getAudio"
        const val TAG = "TtsPluginEngineV3"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // 定义 Console 接口供 JS 调用
    interface JsConsole {
        fun log(msg: String)
        fun error(msg: String)
    }

    // 定义 Network 接口供 JS 调用
    interface JsNetwork {
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
        try {
            // 1. 注入 Console
            quickJs.set("nativeConsole", JsConsole::class.java, object : JsConsole {
                override fun log(msg: String) { Log.i(TAG, "[${plugin.name}] $msg") }
                override fun error(msg: String) { Log.e(TAG, "[${plugin.name}] $msg") }
            })
            quickJs.evaluate("const console = { log: (m) => nativeConsole.log(String(m)), error: (m) => nativeConsole.error(String(m)) };")

            // 2. 注入 Fetch
            quickJs.set("nativeNetwork", JsNetwork::class.java, object : JsNetwork {
                override fun fetch(url: String): String {
                    try {
                        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
                        val resp = client.newCall(req).execute()
                        if (resp.isSuccessful) {
                            return resp.body?.string() ?: ""
                        } else {
                            throw RuntimeException("HTTP Error: ${resp.code}")
                        }
                    } catch (e: Exception) {
                        return "ERROR: ${e.message}"
                    }
                }
            })
            quickJs.evaluate("""
                const fetch = async (url) => {
                    return nativeNetwork.fetch(url);
                };
            """.trimIndent())

            // 3. 执行插件代码
            quickJs.evaluate(plugin.code, "plugin.js")

            // 4. 调用 getAudio
            // 准备参数
            val r = (rate * 50f).toInt()
            val v = (volume * 50f).toInt()
            val p = (pitch * 50f).toInt()

            // 构建调用脚本
            val callScript = """
                if (typeof $OBJ_PLUGIN_JS === 'undefined') throw new Error("$OBJ_PLUGIN_JS 未定义");
                if (typeof $OBJ_PLUGIN_JS.$FUNC_GET_AUDIO !== 'function') throw new Error("$FUNC_GET_AUDIO 未定义");
                
                // 直接调用
                $OBJ_PLUGIN_JS.$FUNC_GET_AUDIO("$text", "$locale", "$voice", $r, $v, $p);
            """.trimIndent()

            val result = quickJs.evaluate(callScript)
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
        return when (result) {
            is String -> {
                if (result.startsWith("http")) {
                    val resp = client.newCall(Request.Builder().url(result).build()).execute()
                    if (!resp.isSuccessful) throw Exception("下载失败: ${resp.code}")
                    resp.body?.byteStream()
                } else {
                    throw Exception("返回必须是 http url")
                }
            }
            else -> throw Exception("不支持的返回类型: ${result::class.java.simpleName}")
        }
    }
}
