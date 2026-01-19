package com.github.jing332.tts.speech.plugin.engine

import android.content.Context
import android.util.Log
import android.widget.LinearLayout
import com.github.jing332.common.utils.dp
import com.github.jing332.common.utils.toCountryFlagEmoji
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.script.toMap
import org.mozilla.javascript.ScriptableObject
import java.util.Locale

/**
 * UI 渲染引擎：通过极致的代码剥离，确保 Rhino 兼容 V3 脚本中的 UI 部分
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
     * 🛠️ 强力防御性执行：
     * 在 Rhino 看到代码前，强制抹除所有 ES6+ 关键字和 PluginJS 逻辑块
     */
    override fun execute(script: String): Any? {
        var finalScript = script
        if (script.contains("\"use quickjs\"", ignoreCase = true) || script.contains("'use quickjs'", ignoreCase = true)) {
            finalScript = finalScript
                .replace("\"use quickjs\"", "")
                .replace("'use quickjs'", "")
                // 1. 抹除 PluginJS 块：兼容 var/let/const 声明，防止其内部的 async 逻辑干扰词法解析
                .replace(Regex("""(var|let|const)\s+PluginJS\s*=\s*\{[\s\S]*?\}\s*;?""", RegexOption.MULTILINE), "var PluginJS = { getAudio: function(){ return ''; } };")
                // 2. 语法转换：将 let/const 统一降级为 var
                .replace(Regex("""\b(let|const)\b"""), "var")
                // 3. 关键字剔除：移除所有 async 和 await，确保 Rhino 的语法树正常构建
                .replace(Regex("""\b(async|await)\b"""), "")
            
            Log.d(TAG, "已完成针对 Rhino 环境的 ES6 强力净化")
        }
        return super.execute(PackageImporter.default + finalScript)
    }

    fun getSampleRate(locale: String, voice: String): Int? = try {
        engine.invokeMethod(editUiJsObject, "getAudioSampleRate", locale, voice)?.run {
            if (this is Int) this else (this as Double).toInt()
        }
    } catch (e: Exception) { null }

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
    } catch (e: Exception) { emptyMap() }

    fun getVoices(locale: String): List<Voice> = try {
        val result = engine.invokeMethod(editUiJsObject, "getVoices", locale)
        if (result is ScriptableObject) {
            result.toMap<Any, Any>().map { (k, v) ->
                val name = if (v is ScriptableObject) v.get("name")?.toString() ?: "" else v.toString()
                Voice(k.toString(), name)
            }
        } else emptyList()
    } catch (e: Exception) { emptyList() }

    fun onLoadData() = try { engine.invokeMethod(editUiJsObject, "onLoadData") } catch (_: Exception) {}
    fun onLoadUI(ctx: Context, container: LinearLayout) = try { engine.invokeMethod(editUiJsObject, "onLoadUI", ctx, container) } catch (_: Exception) {}
    fun onVoiceChanged(locale: String, voice: String) = try { engine.invokeMethod(editUiJsObject, "onVoiceChanged", locale, voice) } catch (_: Exception) {}

    data class Voice(val id: String, val name: String, val icon: String? = null)
}
