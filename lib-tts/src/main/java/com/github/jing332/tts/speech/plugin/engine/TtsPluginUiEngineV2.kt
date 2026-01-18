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
        private const val TAG = "TtsPluginUiEngineV2"

        const val FUNC_SAMPLE_RATE = "getAudioSampleRate"
        const val FUNC_IS_NEED_DECODE = "isNeedDecode"

        const val FUNC_LOCALES = "getLocales"
        const val FUNC_VOICES = "getVoices"

        const val FUNC_ON_LOAD_UI = "onLoadUI"
        const val FUNC_ON_LOAD_DATA = "onLoadData"
        const val FUNC_ON_VOICE_CHANGED = "onVoiceChanged"

        const val OBJ_UI_JS = "EditorJS"
    }

    fun dp(px: Int): Int {
        return px.dp
    }

    // 🛠️ 核心修复：如果找不到 EditorJS，返回一个空的 NativeObject 而不抛出异常
    private val editUiJsObject: ScriptableObject by lazy {
        try {
            (engine.get(OBJ_UI_JS) as? ScriptableObject) ?: org.mozilla.javascript.NativeObject()
        } catch (e: Exception) {
            org.mozilla.javascript.NativeObject()
        }
    }

    override fun execute(script: String): Any? {
        return super.execute(PackageImporter.default + script)
    }

    fun getSampleRate(locale: String, voice: String): Int? {
        runtime.console.debug("getSampleRate($locale, $voice)")

        return try {
            engine.invokeMethod(
                editUiUiJsObject,
                FUNC_SAMPLE_RATE,
                locale,
                voice
            )?.run {
                if (this is Int) this
                else (this as Double).toInt()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isNeedDecode(locale: String, voice: String): Boolean {
        runtime.console.debug("isNeedDecode($locale, $voice)")

        return try {
            engine.invokeMethod(editUiJsObject, FUNC_IS_NEED_DECODE, locale, voice)?.run {
                if (this is Boolean) this
                else (this as Double).toInt() == 1
            } ?: true
        } catch (_: Exception) {
            true
        }
    }

    fun getLocales(): Map<String, String> {
        return try {
            engine.invokeMethod(editUiJsObject, FUNC_LOCALES).run {
                when (this) {
                    is List<*> -> this.associate {
                        val locale = Locale.forLanguageTag(it.toString())
                        val displayName = locale.country.toCountryFlagEmoji() + " " + locale.displayName
                        it.toString() to displayName
                    }

                    is Map<*, *> -> {
                        this.map { (key, value) ->
                            key.toString() to value.toString()
                        }.toMap()
                    }

                    else -> emptyMap()
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun getVoices(locale: String): List<Voice> {
        return try {
            engine.invokeMethod(editUiJsObject, FUNC_VOICES, locale).run {
                when (this) {
                    is ScriptableObject -> {
                        toMap<Any, Any>().map { (key, value) ->
                            ScriptRuntime.toString(key) to value
                        }.map { (key, value) ->
                            var icon: String? = null
                            var name: String = if (value is CharSequence) value.toString() else ""

                            if (value is ScriptableObject) {
                                icon = value.get("iconUrl")?.toString()
                                    ?: value.get("icon")?.toString()

                                name = value.get("name")?.toString() ?: name
                            }

                            Voice(key.toString(), name.toString(), icon)
                        }
                    }

                    else -> emptyList()
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun onLoadData() {
        runtime.console.debug("onLoadData()...")

        try {
            engine.invokeMethod(editUiJsObject, FUNC_ON_LOAD_DATA)
        } catch (_: Exception) {
        }
    }

    fun onLoadUI(context: Context, container: LinearLayout) {
        runtime.console.debug("onLoadUI()...")
        try {
            engine.invokeMethod(
                editUiJsObject,
                FUNC_ON_LOAD_UI,
                context,
                container
            )
        } catch (_: Exception) {
        }
    }

    fun onVoiceChanged(locale: String, voice: String) {
        runtime.console.debug("onVoiceChanged($locale, $voice)")

        try {
            engine.invokeMethod(
                editUiJsObject,
                FUNC_ON_VOICE_CHANGED,
                locale,
                voice
            )
        } catch (_: Exception) {
        }
    }

    data class Voice(val id: String, val name: String, val icon: String? = null)
}
