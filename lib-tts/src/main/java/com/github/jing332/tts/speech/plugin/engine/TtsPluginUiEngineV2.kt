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
     * 🛠️ 严谨修复：针对 V3 插件，在 Rhino 执行前净化 ES6 语法
     */
    override fun execute(script: String): Any? {
        var finalScript = script
        if (script.contains("\"use quickjs\"", ignoreCase = true)) {
            // 屏蔽 PluginJS 块，防止 Rhino 触发词法错误 (如 async/await)
            // 我们通过正则将 PluginJS 替换为简单的模拟对象，仅保留 EditorJS 运行环境
            finalScript = script.replace(Regex("""var\s+PluginJS\s*=\s*\{[\s\S]*?};""", RegexOption.MULTILINE), "var PluginJS = { getAudio: function(){ return ''; } };")
            finalScript = finalScript.replace(Regex("""let\s+PluginJS\s*=\s*\{[\s\S]*?};""", RegexOption.MULTILINE), "let PluginJS = { getAudio: function(){ return ''; } };")
            Log.d(TAG, "V3 脚本已通过正则净化，供 Rhino 渲染 UI")
        }
        return super.execute(PackageImporter.default + finalScript)
    }

    fun getSampleRate(locale: String, voice: String): Int? = try {
        engine.invokeMethod(editUiJsObject, "getAudioSampleRate", locale, voice)?.run {
            if (this is Int) this else (this as Double).toInt()
        }
    } catch (e: Exception) { 
        Log.e(TAG, "getSampleRate 失败: ${e.message}")
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
        Log.e(TAG, "getLocales 失败: ${e.message}")
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
        Log.e(TAG, "getVoices 失败: ${e.message}")
        emptyList() 
    }

    fun onLoadData() = try { engine.invokeMethod(editUiJsObject, "onLoadData") } catch (_: Exception) {}
    fun onLoadUI(ctx: Context, container: LinearLayout) = try { engine.invokeMethod(editUiJsObject, "onLoadUI", ctx, container) } catch (_: Exception) {}
    fun onVoiceChanged(locale: String, voice: String) = try { engine.invokeMethod(editUiJsObject, "onVoiceChanged", locale, voice) } catch (_: Exception) {}

    data class Voice(val id: String, val name: String, val icon: String? = null)
}
