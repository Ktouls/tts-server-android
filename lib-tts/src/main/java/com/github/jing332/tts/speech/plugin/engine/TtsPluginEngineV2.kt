package com.github.jing332.tts.speech.plugin.engine

import android.content.Context
import com.drake.net.Net
import com.github.jing332.common.utils.limitLength
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.script.engine.RhinoScriptEngine
import com.github.jing332.script.ensureArgumentsLength
import com.github.jing332.script.runtime.NativeResponse
import com.github.jing332.script.runtime.console.Console
import com.github.jing332.script.simple.CompatScriptRuntime
import com.github.jing332.script.source.toScriptSource
import com.github.jing332.tts.speech.EmptyInputStream
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import okhttp3.Response
import okhttp3.ResponseBody
import org.mozilla.javascript.Callable
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.typedarrays.NativeArrayBuffer
import org.mozilla.javascript.typedarrays.NativeTypedArrayView
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

open class TtsPluginEngineV2(val context: Context, var plugin: Plugin) {
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
        execute(plugin.code)
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
            is NativeResponse -> result.rawResponse?.body?.byteStream()
            is CharSequence -> {
                val str = result.toString()
                if (str.startsWith("http")) Net.get(str).execute<Response>().body?.byteStream()
                else throw IllegalStateException(str)
            }
            else -> throw IllegalArgumentException("Unsupported return type: ${result.javaClass.name}")
        }
    }

    suspend fun getAudio(text: String, locale: String, voice: String, rate: Float = 1f, volume: Float = 1f, pitch: Float = 1f): InputStream {
        val r = (rate * 50f).toInt(); val v = (volume * 50f).toInt(); val p = (pitch * 50f).toInt()
        
        // 🛠️ 关键修复：捕获 JavaScriptException（如 16882.png 中的超时）防止闪退
        return try {
            val result = try {
                runInterruptible {
                    engine.invokeMethod(pluginJsObj, FUNC_GET_AUDIO, text, locale, voice, r, v, p)
                }
            } catch (_: NoSuchMethodException) {
                val request = mapOf("text" to text, "locale" to locale, "voice" to voice, "rate" to r, "speed" to r, "volume" to v, "pitch" to p)
                val ins = JsBridgeInputStream()
                runInterruptible { engine.invokeMethod(pluginJsObj, FUNC_GET_AUDIO_V2, request, ins.getCallback(Mutex())) }
            }
            handleAudioResult(result) ?: EmptyInputStream
        } catch (e: Exception) {
            console.error("Plugin Synthesis Failed: ${e.message}")
            EmptyInputStream
        }
    }
}
