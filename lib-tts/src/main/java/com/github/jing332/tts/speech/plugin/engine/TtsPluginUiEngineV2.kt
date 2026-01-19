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
 * 增强型 UI 渲染引擎：解决 ES6 语法导致的音色列表空白问题
 */
class TtsPluginUiEngineV2(context: Context, plugin: Plugin) : TtsPluginEngineV2(context, plugin) {
    companion object {
        const val OBJ_UI_JS = "EditorJS"
        const val TAG = "TtsPluginUiEngineV2"
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
     * 🛠️ 严谨净化与补丁注入
     */
    override fun execute(script: String): Any? {
        var finalScript = script
        if (script.contains("\"use quickjs\"", ignoreCase = true) || script.contains("'use quickjs'", ignoreCase = true)) {
            // 1. 注入 ES5 补丁 (Polyfill)
            val polyfill = """
                if (!Object.values) {
                    Object.values = function(obj) {
                        return Object.keys(obj).map(function(key) { return obj[key]; });
                    };
                }
            """.trimIndent()

            finalScript = finalScript
                .replace("\"use quickjs\"", "")
                .replace("'use quickjs'", "")
                // 2. 剥离 PluginJS 块
                .replace(Regex("""(var|let|const)\s+PluginJS\s*=\s*\{[\s\S]*?\}\s*;?""", RegexOption.MULTILINE), "var PluginJS = { getAudio: function(){ return ''; } };")
                // 3. 转换简单的箭头函数 (处理 x => ... 和 (x, y) => ...)
                .replace(Regex("""\(([^)]*)\)\s*=>"""), "function($1)")
                .replace(Regex("""\b([a-zA-Z0-9_$]+)\s*=>"""), "function($1)")
                // 4. 将 const/let 替换为 var
                .replace(Regex("""\b(let|const)\b"""), "var")
                // 5. 移除 async/await
                .replace(Regex("""\b(async|await)\b"""), "")
            
            finalScript = polyfill + "\n" + finalScript
            Log.d(TAG, "已完成针对 Rhino 的高级语法净化与补丁注入")
        }
        
        return try {
            super.execute(PackageImporter.default + finalScript)
        } catch (e: Exception) {
            // 严谨性：如果解析失败，将错误输出到日志以便排查具体的语法冲突点
            Log.e(TAG, "Rhino 解析净化后的脚本失败: ${e.message}")
            null
        }
    }

    // --- 以下保持原样 ---
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
