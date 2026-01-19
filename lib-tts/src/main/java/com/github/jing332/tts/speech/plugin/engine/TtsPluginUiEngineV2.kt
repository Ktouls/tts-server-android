package com.github.jing332.tts.speech.plugin.engine

import android.content.Context
import android.util.Log
import android.widget.LinearLayout
import com.github.jing332.common.utils.dp
import com.github.jing332.common.utils.toCountryFlagEmoji
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.script.toMap
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.ScriptableObject
import java.util.Locale

/**
 * UI 渲染引擎：负责在插件编辑界面加载音色列表及 UI 交互逻辑
 */
class TtsPluginUiEngineV2(context: Context, plugin: Plugin) : TtsPluginEngineV2(context, plugin) {
    companion object {
        const val OBJ_UI_JS = "EditorJS"
    }

    private val editUiJsObject: ScriptableObject by lazy {
        try {
            (engine.get(OBJ_UI_JS) as? ScriptableObject) ?: org.mozilla.javascript.NativeObject()
        } catch (e: Exception) {
            org.mozilla.javascript.NativeObject()
        }
    }

    fun dp(px: Int): Int = px.dp

    /**
     * 🛠️ 严谨修复：ES6 语法净化
     * 针对 V3 插件，在 Rhino 执行前通过正则移除 PluginJS 块，防止词法解析错误导致 UI 崩溃
     */
    override fun execute(script: String): Any? {
        var finalScript = script
        if (script.contains("\"use quickjs\"", ignoreCase = true)) {
            // 将包含 async/await 的 PluginJS 逻辑块替换为 Rhino 可识别的模拟对象
            finalScript = script.replace(Regex("""var\s+PluginJS\s*=\s*\{[\s\S]*?};""", RegexOption.MULTILINE), 
                "var PluginJS = { getAudio: function(){ return ''; } };")
            finalScript = finalScript.replace(Regex("""let\s+PluginJS\s*=\s*\{[\s\S]*?};""", RegexOption.MULTILINE), 
                "let PluginJS = { getAudio: function(){ return ''; } };")
            Log.d(TAG, "V3 脚本 UI 兼容性净化完成")
        }
        return super.execute(PackageImporter.default + finalScript)
    }

    fun getSampleRate(locale: String, voice: String): Int? = try {
        engine.invokeMethod(editUiJsObject, "getAudioSampleRate", locale, voice)?.run {
            if (this is Int) this else (this as Double).toInt()
        }
    } catch (e: Exception) { 
        Log.e(TAG, "获取采样率失败: ${e.message}")
        null 
    }

    fun isNeedDecode(locale: String, voice: String): Boolean = try {
        engine.invokeMethod(editUiJsObject, "isNeedDecode", locale, voice)?.run {
            if (this is Boolean) this else (this as Double).toInt() == 1
        } ?: true
    } catch (_: Exception) { true }

    fun getLocales(): Map<String, String> = try {
        val result = engine.invokeMethod(editUiJsObject, "getLocales")
        when (result) {
            is List<*> -> result.associate {
                val loc = Locale.forLanguageTag(it.toString())
                it.toString() to (loc.country.toCountryFlagEmoji() + " " + loc.displayName)
            }
            is Map<*, *> -> result.map { it.key.toString() to it.value.toString() }.toMap()
            else -> emptyMap()
        }
    } catch (e: Exception) { 
        Log.e(TAG, "获取语言列表失败: ${e.message}")
        emptyMap() 
    }

    fun getVoices(locale: String): List<Voice> = try {
        val result = engine.invokeMethod(editUiJsObject, "getVoices", locale)
        if (result is ScriptableObject) {
            result.toMap<Any, Any>().map { (k, v) ->
                val name = if (v is ScriptableObject) v.get("name")?.toString() ?: "" else v.toString()
                Voice(k.toString(), name)
            }
        } else emptyList()
    } catch (e: Exception) { 
        Log.e(TAG, "获取音色列表失败: ${e.message}")
        emptyList() 
    }

    fun onLoadData() = try { engine.invokeMethod(editUiJsObject, "onLoadData") } catch (_: Exception) {}
    fun onLoadUI(ctx: Context, container: LinearLayout) = try { engine.invokeMethod(editUiJsObject, "onLoadUI", ctx, container) } catch (_: Exception) {}
    fun onVoiceChanged(locale: String, voice: String) = try { engine.invokeMethod(editUiJsObject, "onVoiceChanged", locale, voice) } catch (_: Exception) {}

    data class Voice(val id: String, val name: String, val icon: String? = null)
}
