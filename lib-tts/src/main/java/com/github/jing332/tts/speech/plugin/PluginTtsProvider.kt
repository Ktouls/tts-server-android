package com.github.jing332.tts.speech.plugin

import android.content.Context
import android.util.Log
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.tts.speech.EngineState
import com.github.jing332.tts.speech.TextToSpeechProvider
import com.github.jing332.tts.speech.plugin.engine.TtsPluginEngineV2
import com.github.jing332.tts.speech.plugin.engine.TtsPluginEngineV3
import com.github.jing332.tts.synthesizer.SystemParams
import java.io.InputStream
import java.util.regex.Pattern

open class PluginTtsProvider(
    val context: Context,
    val plugin: Plugin,
) : TextToSpeechProvider<PluginTtsSource>() {

    companion object {
        const val TAG = "PluginTtsProvider"
        // 只检测 async 和 await
        private val ASYNC_FEATURES = Pattern.compile("\\b(async|await)\\b", Pattern.CASE_INSENSITIVE)
    }

    private var mEngine: TtsPluginEngineV2? = null
    private var mEngineV3: TtsPluginEngineV3? = null

    // 👇👇👇 关键修复：补回了这个被我误删的公开属性 👇👇👇
    var engine: TtsPluginEngineV2?
        get() = mEngine
        set(value) { mEngine = value }

    override var state: EngineState = EngineState.Uninitialized()

    override suspend fun getStream(params: SystemParams, source: PluginTtsSource): InputStream {
        val speed = if (source.speed == 0f) params.speed else source.speed
        val volume = if (source.volume == 0f) params.volume else source.volume
        val pitch = if (source.pitch == 0f) params.pitch else source.pitch

        return try {
            if (mEngineV3 != null) {
                mEngineV3!!.getAudio(params.text, source.locale, source.voice, speed, volume, pitch)
                    ?: throw IllegalStateException("QuickJS Engine returned null")
            } else {
                mEngine?.source = source
                mEngine?.getAudio(params.text, source.locale, source.voice, speed, volume, pitch)
                    ?: throw IllegalStateException("Engine not initialized")
            }
        } catch (e: Exception) {
            state = EngineState.Uninitialized()
            throw e
        }
    }

    override suspend fun onInit() {
        state = EngineState.Initializing

        // 1. 显式标记 "use quickjs"
        val hasTag = plugin.code.contains("\"use quickjs\"", ignoreCase = true)
        // 2. 异步特征检测 async/await
        val hasAsync = ASYNC_FEATURES.matcher(plugin.code).find()

        if (hasTag || hasAsync) {
            Log.i(TAG, "启用 QuickJS: ${plugin.name}")
            if (mEngineV3 == null) mEngineV3 = TtsPluginEngineManager.getV3(context, plugin)
        } else {
            // 3. 默认走 Rhino (V2)
            if (mEngine == null) {
                mEngine = TtsPluginEngineManager.get(context, plugin)
            }
        }
        state = EngineState.Initialized
    }

    override fun onStop() {
        super.onStop()
        mEngine?.onStop()
    }

    override fun onDestroy() {
        state = EngineState.Uninitialized()
        mEngine?.onStop()
        mEngine = null
        mEngineV3 = null
    }
}
