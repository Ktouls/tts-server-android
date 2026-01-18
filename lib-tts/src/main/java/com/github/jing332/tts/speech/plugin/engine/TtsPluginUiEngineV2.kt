package com.github.jing332.tts.speech.plugin.engine

import android.content.Context
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

    override fun execute(script: String): Any? = super.execute(PackageImporter.default + script)

    fun getSampleRate(locale: String, voice: String): Int? = try {
        engine.invokeMethod(editUiJsObject, "getAudioSampleRate", locale, voice)?.run {
            if (this is Int) this else (this as Double).toInt()
        }
    } catch (_: Exception) { null }

    fun isNeedDecode(locale: String, voice: String): Boolean = try {
        engine.invokeMethod(editUiJsObject, "isNeedDecode", locale, voice)?.run {
            if (this is Boolean) this else (this as Double).toInt() == 1
        } ?: true
    } catch (_: Exception) { true }

    fun getLocales(): Map<String, String> = try {
        engine.invokeMethod(editUiJsObject, "getLocales").run {
            when (this) {
                is List<*> -> this.associate {
                    val loc = Locale.forLanguageTag(it.toString())
                    it.toString() to (loc.country.toCountryFlagEmoji() + " " + loc.displayName)
                }
                is Map<*, *> -> this.map { it.key.toString() to it.value.toString() }.toMap()
                else -> emptyMap()
            }
        }
    } catch (_: Exception) { emptyMap() }

    fun getVoices(locale: String): List<Voice> = try {
        engine.invokeMethod(editUiJsObject, "getVoices", locale).run {
            if (this is ScriptableObject) {
                toMap<Any, Any>().map { (k, v) ->
                    val name = if (v is ScriptableObject) v.get("name")?.toString() ?: "" else v.toString()
                    Voice(k.toString(), name)
                }
            } else emptyList()
        }
    } catch (_: Exception) { emptyList() }

    fun onLoadData() = try { engine.invokeMethod(editUiJsObject, "onLoadData") } catch (_: Exception) {}
    fun onLoadUI(ctx: Context, container: LinearLayout) = try { engine.invokeMethod(editUiJsObject, "onLoadUI", ctx, container) } catch (_: Exception) {}
    fun onVoiceChanged(locale: String, voice: String) = try { engine.invokeMethod(editUiJsObject, "onVoiceChanged", locale, voice) } catch (_: Exception) {}

    data class Voice(val id: String, val name: String, val icon: String? = null)
}
