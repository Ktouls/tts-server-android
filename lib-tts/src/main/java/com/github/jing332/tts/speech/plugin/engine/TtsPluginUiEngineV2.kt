package com.github.jing332.tts.speech.plugin.engine

import android.content.Context
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.script.runtime.console.Console
import io.github.oshai.kotlinlogging.KotlinLogging
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * V2 引擎 (基于 Rhino)
 * 职责：负责 UI 界面渲染、获取插件元数据。
 * 特性：增加对 V3 (ES6) 代码的兼容性清洗，防止解析报错。
 */
// 🛠️ 修正1：类名改回 TtsPluginEngineV2，以匹配 Manager 的引用
class TtsPluginEngineV2(
    private val context: Context,
    var plugin: Plugin,
    // 🛠️ 修正2：超时参数改为 Long 类型，以匹配 Manager 的传参
    private val timeoutMs: Long = 5000L
) {
    companion object {
        const val TAG = "TtsPluginEngineV2"
        private val logger = KotlinLogging.logger(TAG)
    }

    private val rhino: org.mozilla.javascript.Context = org.mozilla.javascript.Context.enter()
    private val scope: Scriptable = rhino.initStandardObjects()

    var console: Console = Console()
    var source: PluginTtsSource = PluginTtsSource()

    init {
        rhino.optimizationLevel = -1
        rhino.languageVersion = org.mozilla.javascript.Context.VERSION_ES6
    }

    /**
     * 加载并解析脚本
     * 关键修复：在此处清洗 ES6 语法，防止 Rhino 崩溃
     */
    fun eval(): Any? {
        var script = plugin.code

        // 🛡️ 针对 V3 (QuickJS) 插件的终极兼容性处理
        if (script.contains("\"use quickjs\"") || script.contains("'use quickjs'")) {
            logger.info { "Detected V3 plugin, applying SUPER strict sanitization for Rhino..." }

            script = script
                // 1. 【核心救命补丁】清洗模板字符串 (反引号)
                .replace(Regex("`[\\s\\S]*?`"), "\"\"")
                // 2. 降级变量声明
                .replace(Regex("""\b(let|const)\b"""), "var")
                // 3. 移除异步关键字
                .replace(Regex("""\b(async|await)\b"""), "")
                // 4. 暴力清空 getAudio 函数体
                .replace(Regex("""getAudio\s*:\s*(function)?\s*\(.*?\)\s*(=>)?\s*\{([\s\S]*?)\}"""), "getAudio: function(){}")
                // 5. 箭头函数降级
                .replace(Regex("""\((.*?)\)\s*=>"""), "function($1)")
        }

        // 🛠️ 修正3：使用全限定名 org.mozilla.javascript.Context 避免冲突
        val ttsrv = org.mozilla.javascript.Context.javaToJS(PluginTtsServer(context, source), scope)
        ScriptableObject.putProperty(scope, "ttsrv", ttsrv)
        
        ScriptableObject.putProperty(scope, "console", org.mozilla.javascript.Context.javaToJS(console, scope))

        return rhino.evaluateString(scope, script, plugin.pluginId, 1, null)
    }

    fun onLoad() {
        callMethod("onLoad")
    }

    fun onStop() {
        try {
            org.mozilla.javascript.Context.exit()
        } catch (_: Exception) {
        }
    }

    // 获取采样率
    fun getSampleRate(locale: String, voice: String): Int {
        val obj = getEditorJs() ?: return 16000
        val func = obj.get("getAudioSampleRate", scope)
        if (func is org.mozilla.javascript.Function) {
            val result = func.call(rhino, scope, obj, arrayOf(locale, voice))
            return (result as? Number)?.toInt() ?: 16000
        }
        return 16000
    }

    // 是否需要解码
    fun isNeedDecode(locale: String, voice: String): Boolean {
        val obj = getEditorJs() ?: return false
        val func = obj.get("isNeedDecode", scope)
        if (func is org.mozilla.javascript.Function) {
            val result = func.call(rhino, scope, obj, arrayOf(locale, voice))
            return result as? Boolean ?: true
        }
        return true
    }

    // 旧版获取音频
    fun getAudio(text: String, locale: String, voice: String): InputStream {
        val pluginJs = scope.get("PluginJS", scope) as? Scriptable ?: throw Exception("Object PluginJS not found")
        val func = pluginJs.get("getAudio", scope)
        if (func is org.mozilla.javascript.Function) {
            val result = func.call(rhino, scope, pluginJs, arrayOf(text, locale, voice, 50, 50, 50))
            return ByteArrayInputStream(result.toString().toByteArray())
        }
        throw Exception("Function getAudio not found")
    }

    private fun getEditorJs(): Scriptable? {
        val obj = scope.get("EditorJS", scope)
        return if (obj is Scriptable) obj else null
    }

    private fun callMethod(methodName: String) {
        val pluginJs = scope.get("PluginJS", scope) as? Scriptable ?: return
        val func = pluginJs.get(methodName, scope)
        if (func is org.mozilla.javascript.Function) {
            func.call(rhino, scope, pluginJs, arrayOf())
        }
    }

    class PluginTtsServer(val context: Context, val source: PluginTtsSource) {
        val userVars: NativeObject
            get() = NativeObject()

        val tts: NativeObject
            get() {
                val ttsObj = NativeObject()
                val dataObj = NativeObject()
                ttsObj.put("data", ttsObj, dataObj)
                return ttsObj
            }
            
        fun fileExist(path: String): Boolean = false
        fun readTxtFile(path: String): String = "{}"
        fun writeTxtFile(path: String, text: String) {}
        fun httpGetString(url: String, headers: Any?): String = "{}"
    }
}
