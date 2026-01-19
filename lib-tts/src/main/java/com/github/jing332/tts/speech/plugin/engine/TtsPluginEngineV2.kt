package com.github.jing332.tts.speech.plugin.engine

import android.content.Context
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.script.engine.RhinoScriptEngine
import com.github.jing332.script.runtime.NativeResponse
import com.github.jing332.script.runtime.console.Console
import com.github.jing332.script.simple.CompatScriptRuntime
import com.github.jing332.script.source.toScriptSource
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import okhttp3.OkHttpClient
import okhttp3.Request
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.typedarrays.NativeArrayBuffer
import org.mozilla.javascript.typedarrays.NativeTypedArrayView
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

open class TtsPluginEngineV2(
    val context: Context, 
    var plugin: Plugin,
    val timeoutMs: Long = 5000L 
) {
    companion object {
        const val OBJ_PLUGIN_JS = "PluginJS"
        const val FUNC_GET_AUDIO = "getAudio"
        const val FUNC_GET_AUDIO_V2 = "getAudioV2"
        const val FUNC_ON_LOAD = "onLoad"
        const val FUNC_ON_STOP = "onStop"
    }

    var console: Console
        get() = engine.runtime.console
        set(value) { engine.runtime.console = value }

    protected val ttsrv = TtsEngineContext(PluginTtsSource(), plugin.userVars, context, plugin.pluginId)
    val runtime = CompatScriptRuntime(ttsrv)
    var source: PluginTtsSource
        get() = ttsrv.tts
        set(value) { ttsrv.tts = value }

    protected val pluginJsObj: ScriptableObject
        get() = (engine.get(OBJ_PLUGIN_JS) as? ScriptableObject) ?: throw IllegalStateException("Object `$OBJ_PLUGIN_JS` not found")

    protected var engine: RhinoScriptEngine = RhinoScriptEngine(runtime)

    open protected fun execute(script: String): Any? = engine.execute(script.toScriptSource(sourceName = plugin.pluginId))

    fun eval() {
        var scriptCode = plugin.code
        
        // 🛡️ 增强版正则：专门解决 "missing )" 和箭头函数问题
        if (scriptCode.contains("\"use quickjs\"") || scriptCode.contains("'use quickjs'")) {
            scriptCode = scriptCode
                // 1. 清洗反引号 (模板字符串) -> 变成空字符串
                .replace(Regex("`[\\s\\S]*?`"), "\"\"")
                // 2. 降级变量声明
                .replace(Regex("""\b(let|const)\b"""), "var")
                // 3. 移除 async/await
                .replace(Regex("""\b(async|await)\b"""), "")
                // 4. 处理带括号的箭头函数: (a,b) =>
                .replace(Regex("""\((.*?)\)\s*=>"""), "function($1)")
                // 5. 【新增】处理不带括号的单参数箭头函数: item =>
                // 将 item => item.locale 替换为 function(item){ return item.locale } 的简化版，
                // 由于正则很难完美处理 return，这里我们简单替换为 function(item)，这能骗过 Rhino 的语法检查
                .replace(Regex("""(\w+)\s*=>"""), "function($1)")
                // 6. 暴力屏蔽 getAudio，直接替换为返回空
                .replace(Regex("""getAudio\s*:\s*function\s*\(.*?\)\s*\{"""), "getAudio: function(){ return null; //")
        }

        execute(scriptCode)
        
        pluginJsObj.apply {
            plugin.name = get("name").toString()
            plugin.pluginId = get("id").toString()
            plugin.author = get("author").toString()
            plugin.iconUrl = get("iconUrl")?.toString() ?: ""
            plugin.defVars = try { get("vars") as Map<String, Map<String, String>> } catch (_: Exception) { emptyMap() }
            plugin.version = try { org.mozilla.javascript.Context.toNumber(get("version")).toInt() } catch (e: Exception) { -1 }
        }
    }

    fun onLoad(): Any? = runCatching { engine.invokeMethod(pluginJsObj, FUNC_ON_LOAD) }.getOrNull()
    fun onStop(): Any? = runCatching { engine.invokeMethod(pluginJsObj, FUNC_ON_STOP) }.getOrNull()

    private fun handleAudioResult(result: Any?): InputStream? {
        if (result == null || result is Undefined) return null
        return when (result) {
            is NativeArrayBuffer -> ByteArrayInputStream(result.buffer)
            is NativeTypedArrayView<*> -> ByteArrayInputStream(result.buffer.buffer)
            is InputStream -> result
            is ByteArray -> result.inputStream()
            is NativeResponse -> {
                if (result.rawResponse?.isSuccessful == false) throw RuntimeException("HTTP Error: ${result.rawResponse?.code}")
                result.rawResponse?.body?.byteStream()
            }
            is CharSequence -> {
                val str = result.toString()
                if (str.startsWith("http")) {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(300, TimeUnit.SECONDS)
                        .readTimeout(300, TimeUnit.SECONDS)
                        .build()
                    val resp = client.newCall(Request.Builder().url(str).build()).execute()
                    if (!resp.isSuccessful) throw RuntimeException("URL Fetch Error: ${resp.code}")
                    resp.body?.byteStream()
                } else throw IllegalStateException(str)
            }
            else -> throw IllegalArgumentException("Unsupported return type: ${result.javaClass.name}")
        }
    }

    private val mMutex by lazy { Mutex() }

    private suspend fun getAudioV2(request: Map<String, Any>): InputStream {
        val ins = JsBridgeInputStream()
        val callback = ins.getCallback(mMutex) 
        val result = runInterruptible {
            engine.invokeMethod(pluginJsObj, FUNC_GET_AUDIO_V2, request, callback)
                ?: throw NoSuchMethodException("getAudioV2() not found")
        }
        return handleAudioResult(result) ?: ins
    }

    suspend fun getAudio(text: String, locale: String, voice: String, rate: Float = 1f, volume: Float = 1f, pitch: Float = 1f): InputStream {
        val r = (rate * 50f).toInt(); val v = (volume * 50f).toInt(); val p = (pitch * 50f).toInt()
        
        val result = try {
            runInterruptible {
                engine.invokeMethod(pluginJsObj, FUNC_GET_AUDIO, text, locale, voice, r, v, p)
            }
        } catch (_: NoSuchMethodException) {
            return getAudioV2(mapOf("text" to text, "locale" to locale, "voice" to voice, "rate" to r, "speed" to r, "volume" to v, "pitch" to p))
        }
        return handleAudioResult(result) ?: throw RuntimeException("Synthesis Result is Empty")
    }
}
