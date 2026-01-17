package com.github.jing332.tts.speech.plugin

import android.content.Context
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.tts.speech.EngineState
import com.github.jing332.tts.speech.TextToSpeechProvider
import com.github.jing332.tts.speech.plugin.engine.TtsPluginEngineV2
import com.github.jing332.tts.synthesizer.SystemParams
import java.io.InputStream

open class PluginTtsProvider(
    val context: Context,
    val plugin: Plugin,
) : TextToSpeechProvider<PluginTtsSource>() {

    private var mEngine: TtsPluginEngineV2? = null

    var engine: TtsPluginEngineV2?
        get() = mEngine
        set(value) {
            mEngine = value
        }

    override var state: EngineState = EngineState.Uninitialized()

    override suspend fun getStream(params: SystemParams, source: PluginTtsSource): InputStream {
        val speed = if (source.speed == 0f) params.speed else source.speed
        val volume = if (source.volume == 0f) params.volume else source.volume
        val pitch = if (source.pitch == 0f) params.pitch else source.pitch

        // source.data mapping to ttsrv.tts.data for javascript
        mEngine?.source = source

        // 修正：增加异常捕获与状态重置，确保在断网后能自动触发重连自愈
        return try {
            mEngine?.getAudio(
                text = params.text,
                locale = source.locale,
                voice = source.voice,
                rate = speed,
                volume = volume,
                pitch = pitch
            ) ?: throw IllegalStateException("Engine not initialized: ${plugin.pluginId}")
        } catch (e: Exception) {
            // 修正：发生网络或其他异常时，重置引擎状态为未初始化
            // 这将强制下一次请求重新执行 onInit()，从而实现网络恢复后的自愈
            state = EngineState.Uninitialized()
            throw e
        }
    }

    override suspend fun onInit() {
        state = EngineState.Initializing
        if (mEngine == null)
            mEngine = TtsPluginEngineManager.get(context, plugin)

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
    }
}
