package com.github.jing332.tts.speech.plugin.engine

import android.content.Context
import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.defineFunction
import com.dokar.quickjs.binding.function
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.tts.speech.EmptyInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 基于 QuickJS 的 V3 插件引擎
 * 支持 ES2020+ 现代语法 (const, let, async/await, 箭头函数等)
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

    suspend fun getAudio(
        text: String,
        locale: String,
        voice: String,
        rate: Float = 1f,
        volume: Float = 1f,
        pitch: Float = 1f
    ): InputStream? = withContext(Dispatchers.IO) {
        // 创建 QuickJS 实例
        QuickJs.create().use { js ->
            // 1. 注入 console.log
            js.defineFunction("console") {
                function("log") { args ->
                    val msg = args.joinToString(" ")
                    Log.i(TAG, "[${plugin.name}] $msg")
                    // 也可以广播出去给日志窗口，这里简化处理直接打印
                }
                function("error") { args ->
                    val msg = args.joinToString(" ")
                    Log.e(TAG, "[${plugin.name}] $msg")
                }
            }

            // 2. 注入简单的 HTTP fetch 功能 (Polyfill)
            // 这是一个简化版的 fetch，为了让插件能发请求
            js.defineFunction("nativeFetch") { url: String ->
                try {
                    val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
                    val resp = client.newCall(req).execute()
                    if (resp.isSuccessful) {
                        resp.body?.string() ?: ""
                    } else {
                        throw Exception("HTTP Error: ${resp.code}")
                    }
                } catch (e: Exception) {
                    throw e
                }
            }
            
            // 在 JS 里包装一层 fetch
            js.evaluate("""
                const fetch = async (url) => {
                    return nativeFetch(url);
                };
            """.trimIndent())

            // 3. 执行插件代码
            try {
                js.evaluate(plugin.code, filename = "plugin.js")
            } catch (e: Exception) {
                Log.e(TAG, "插件加载失败: ${e.message}")
                throw e
            }

            // 4. 准备参数
            // 这里我们需要手动拼接调用，或者使用 QuickJS 的 invoke
            // 为了兼容 V2 接口参数：text, locale, voice, rate, volume, pitch
            // 注意：V2 的 rate/volume/pitch 是 0-100 的整数，这里传入的是 float，需要转换
            val r = (rate * 50f).toInt()
            val v = (volume * 50f).toInt()
            val p = (pitch * 50f).toInt()

            // 5. 调用 getAudio
            // 我们构建一个 JS 脚本来调用函数并返回结果
            val callScript = """
                if (typeof $OBJ_PLUGIN_JS === 'undefined') {
                    throw new Error("$OBJ_PLUGIN_JS 对象未定义，请检查插件代码");
                }
                if (typeof $OBJ_PLUGIN_JS.$FUNC_GET_AUDIO !== 'function') {
                    throw new Error("$FUNC_GET_AUDIO 方法未定义");
                }
                // 调用并等待结果 (支持 async)
                $OBJ_PLUGIN_JS.$FUNC_GET_AUDIO("$text", "$locale", "$voice", $r, $v, $p);
            """.trimIndent()

            val result = js.evaluate(callScript)

            // 6. 处理结果
            return@use handleResult(result)
        }
    }

    private fun handleResult(result: Any?): InputStream? {
        if (result == null) return null
        
        return when (result) {
            is String -> {
                // 如果返回的是 HTTP 地址，自动下载
                if (result.startsWith("http")) {
                    val resp = client.newCall(Request.Builder().url(result).build()).execute()
                    if (!resp.isSuccessful) throw Exception("下载音频失败: ${resp.code}")
                    resp.body?.byteStream()
                } else {
                    throw Exception("不支持的字符串返回类型，必须是 http 开头的 url")
                }
            }
            is ByteArray -> ByteArrayInputStream(result)
            else -> throw Exception("不支持的返回类型: ${result::class.java.simpleName}")
        }
    }
}
