package com.github.jing332.tts.speech.plugin.engine

import android.content.Context
import android.util.Log
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

open class TtsPluginEngineV2(val context: Context, var plugin: Plugin) {
    companion object {
        const val OBJ_PLUGIN_JS = "PluginJS"
        const val FUNC_GET_AUDIO = "getAudio"
        const val FUNC_GET_AUDIO_V2 = "getAudioV2"
        const val FUNC_ON_LOAD = "onLoad"
        const val FUNC_ON_STOP = "onStop"
        const val TAG = "TtsPluginEngineV2"
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
        // 🛠️ 严谨逻辑：只要有 use quickjs，绝对禁止 Rhino 触摸代码，直接走增强版正则提取
        if (plugin.code.contains("\"use quickjs\"", ignoreCase = true)) {
            extractMetadataStrictly()
            return
        }

        try {
            execute(plugin.code)
            pluginJsObj.apply {
                plugin.name = get("name")?.toString() ?: ""
                plugin.pluginId = get("id")?.toString() ?: ""
                plugin.author = get("author")?.toString() ?: ""
                plugin.iconUrl = get("iconUrl")?.toString() ?: ""
                plugin.defVars = try { 
                    val vars = get("vars")
                    if (vars is Map<*, *>) vars as Map<String, Map<String, String>> else emptyMap()
                } catch (_: Exception) { emptyMap() }
                plugin.version = try { org.mozilla.javascript.Context.toNumber(get("version")).toInt() } catch (e: Exception) { -1 }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Rhino 预解析失败，执行兜底提取: ${e.message}")
            extractMetadataStrictly()
        }
    }

    /**
     * 🛠️ 增强版严谨正则提取：
     * 1. 限制搜索范围在 PluginJS 对象定义的起始 1000 字符内，防止匹配到 voices。
     * 2. 强制重置变量配置，消除 UI 误导。
     */
    private fun extractMetadataStrictly() {
        val code = plugin.code
        val startIdx = code.indexOf(OBJ_PLUGIN_JS).coerceAtLeast(0)
        // 截取 PluginJS 定义后的局部范围进行精准匹配
        val searchScope = code.substring(startIdx, (startIdx + 1000).coerceAtMost(code.length))

        fun find(key: String): String? {
            // 匹配 'key': 'value', "key": "value", 或 key: "value"
            val pattern = """['"]?$key['"]?\s*[:=]\s*['"](.*?)['"]""".toRegex()
            return pattern.find(searchScope)?.groupValues?.get(1)
        }

        plugin.name = find("name") ?: plugin.name.ifEmpty { "未命名V3" }
        plugin.pluginId = find("id") ?: plugin.pluginId.ifEmpty { "v3_default_id" }
        plugin.author = find("author") ?: "anonymous"
        plugin.iconUrl = find("iconUrl") ?: ""
        
        // 关键：V3 插件目前不需要通过旧 UI 设置变量，强制清空
        plugin.defVars = emptyMap()
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
                    val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).build()
                    val resp = client.newCall(Request.Builder().url(str).build()).execute()
                    if (!resp.isSuccessful) throw RuntimeException("Download failed: ${resp.code}")
                    resp.body?.byteStream()
                } else throw IllegalStateException(str)
            }
            else -> throw IllegalArgumentException("Type: ${result.javaClass.name}")
        }
    }

    private val mMutex by lazy { Mutex() }

    private suspend fun getAudioV2(request: Map<String, Any>): InputStream {
        val ins = JsBridgeInputStream()
        val callback = ins.getCallback(mMutex) 
        val result = runInterruptible {
            engine.invokeMethod(pluginJsObj, FUNC_GET_AUDIO_V2, request, callback)
                ?: throw NoSuchMethodException("getAudioV2 not found")
        }
        return handleAudioResult(result) ?: ins
    }

    suspend fun getAudio(text: String, locale: String, voice: String, rate: Float = 1f, volume: Float = 1f, pitch: Float = 1f): InputStream {
        val r = (rate * 50f).toInt(); val v = (volume * 50f).toInt(); val p = (pitch * 50f).toInt()
        val result = try {
            runInterruptible { engine.invokeMethod(pluginJsObj, FUNC_GET_AUDIO, text, locale, voice, r, v, p) }
        } catch (_: NoSuchMethodException) {
            return getAudioV2(mapOf("text" to text, "locale" to locale, "voice" to voice, "rate" to r, "speed" to r, "volume" to v, "pitch" to p))
        }
        return handleAudioResult(result) ?: throw RuntimeException("Empty synthesis result")
    }
}
