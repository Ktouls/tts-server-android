package com.github.jing332.tts.speech.plugin

import android.content.Context
import android.util.Log
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.tts.speech.EngineState
import com.github.jing332.tts.speech.TextToSpeechProvider
import com.github.jing332.tts.speech.plugin.engine.TtsPluginEngineManager
import com.github.jing332.tts.speech.plugin.engine.TtsPluginEngineV2
import com.github.jing332.tts.speech.plugin.engine.TtsPluginEngineV3
import com.github.jing332.tts.synthesizer.SystemParams
import java.io.InputStream

/**
 * 路由分发器：支持注入式超时控制
 */
open class PluginTtsProvider(
    val context: Context,
    val plugin: Plugin,
    private val requestTimeout: Long // 🛡️ 通过构造函数注入超时，解耦模块依赖
) : TextToSpeechProvider<PluginTtsSource>() {

    companion object {
        const val TAG = "PluginTtsProvider"
    }

    private var mEngine: TtsPluginEngineV2? = null
    private var mEngineV3: TtsPluginEngineV3? = null

    override var state: EngineState = EngineState.Uninitialized()

    override suspend fun getStream(params: SystemParams, source: PluginTtsSource): InputStream {
        val speed = if (source.speed == 0f) params.speed else source.speed
        val volume = if (source.volume == 0f) params.volume else source.volume
        val pitch = if (source.pitch == 0f) params.pitch else source.pitch

        return if (mEngineV3 != null) {
            mEngineV3!!.getAudio(params.text, source.locale, source.voice, speed, volume, pitch)
                ?: throw IllegalStateException("QuickJS Engine returned null")
        } else {
            mEngine?.source = source
            mEngine?.getAudio(params.text, source.locale, source.voice, speed, volume, pitch)
                ?: throw IllegalStateException("V2 Engine not initialized")
        }
    }

    override suspend fun onInit() {
        state = EngineState.Initializing

        val isQuickJs = plugin.code.contains("\"use quickjs\"", ignoreCase = true)

        if (isQuickJs) {
            Log.i(TAG, "检测到强制指令，启用 QuickJS (V3): ${plugin.name}")
            // 🛡️ 将注入的超时参数转发给管理器
            mEngineV3 = TtsPluginEngineManager.getV3(context, plugin, requestTimeout)
        } else {
            Log.i(TAG, "默认启用 Rhino (V2): ${plugin.name}")
            // 🛡️ 将注入的超时参数转发给管理器
            mEngine = TtsPluginEngineManager.get(context, plugin, requestTimeout)
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
