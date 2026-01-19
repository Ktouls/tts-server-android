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

    /**
     * 🛠️ 修正：增加防御性初始化检测
     */
    private val editUiJsObject: ScriptableObject by lazy {
        try {
            val obj = engine.get(OBJ_UI_JS)
            // 🛡️ 防御性检查：如果是 V3 插件，eval() 时没有执行脚本，此处 OBJ_UI_JS 必然为空
            if (obj == null || obj is org.mozilla.javascript.Undefined) {
                Log.d(TAG, "检测到 EditorJS 未初始化，正在执行净化脚本...")
                execute(plugin.code)
            }
            (engine.get(OBJ_UI_JS) as? ScriptableObject) ?: org.mozilla.javascript.NativeObject()
        } catch (e: Exception) {
            Log.e(TAG, "初始化 EditorJS 失败: ${e.message}")
            org.mozilla.javascript.NativeObject()
        }
    }

    fun dp(px: Int): Int = px.dp

    /**
     * 🛠️ 防御性净化执行：
     * 1. 注入 ES5 兼容补丁。
     * 2. 避开难以匹配的嵌套大括号，采用变量重命名屏蔽法。
     * 3. 针对性修复 Rhino 不支持的 ES6 箭头函数语法。
     */
    override fun execute(script: String): Any? {
        var finalScript = script
        if (script.contains("\"use quickjs\"", ignoreCase = true) || script.contains("'use quickjs'", ignoreCase = true)) {
            try {
                // 1. 注入更稳健的 ES5 补丁
                val polyfill = """
                    if (!Object.values) {
                        Object.values = function(obj) {
                            var vals = [];
                            for (var key in obj) {
                                if (Object.prototype.hasOwnProperty.call(obj, key)) vals.push(obj[key]);
                            }
                            return vals;
                        };
                    }
                """.trimIndent()

                finalScript = finalScript
                    .replace("\"use quickjs\"", "")
                    .replace("'use quickjs'", "")
                    // 2. 🛡️ 防御性屏蔽：将 PluginJS 重命名，避免 Rhino 尝试调用其包含 async 的方法
                    .replace(Regex("""\b(let|const|var)\s+PluginJS\b"""), "var __V3_PluginJS_HIDDEN__")
                    // 3. 🛡️ 语法转换：将 const/let 降级，并移除 async/await 关键字
                    .replace(Regex("""\b(let|const)\b"""), "var")
                    .replace(Regex("""\b(async|await)\b"""), "")
                    // 4. 🛡️ 箭头函数净化：针对 getLocales 和 getVoices 中的常用模式进行转换
                    .replace(Regex("""\.map\s*\(\s*([a-zA-Z0-9_${'$'}]+)\s*=>\s*([^)]+)\)"""), ".map(function($1){return $2})")
                    .replace(Regex("""\.reduce\s*\(\s*\(([^)]+)\)\s*=>\s*\{"""), ".reduce(function($1){")

                finalScript = polyfill + "\n" + finalScript
                
                // 🛠️ 调试日志：分段打印净化后的脚本
                finalScript.chunked(2000).forEach { Log.d(TAG, "Cleaned Script: $it") }

            } catch (e: Exception) {
                Log.e(TAG, "净化过程发生异常: ${e.message}")
            }
        }
        
        return try {
            super.execute(PackageImporter.default + finalScript)
        } catch (e: Exception) {
            Log.e(TAG, "Rhino 解析净化脚本失败: ${e.message}")
            null
        }
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
    } catch (e: Exception) { 
        Log.e(TAG, "getLocales 执行失败: ${e.message}")
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
        Log.e(TAG, "getVoices 执行失败: ${e.message}")
        emptyList() 
    }

    fun onLoadData() = try { engine.invokeMethod(editUiJsObject, "onLoadData") } catch (e: Exception) { Log.e(TAG, "onLoadData 错误: ${e.message}") }
    fun onLoadUI(ctx: Context, container: LinearLayout) = try { engine.invokeMethod(editUiJsObject, "onLoadUI", ctx, container) } catch (e: Exception) { Log.e(TAG, "onLoadUI 错误: ${e.message}") }
    fun onVoiceChanged(locale: String, voice: String) = try { engine.invokeMethod(editUiJsObject, "onVoiceChanged", locale, voice) } catch (_: Exception) {}

    data class Voice(val id: String, val name: String, val icon: String? = null)
}
