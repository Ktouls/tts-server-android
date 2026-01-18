package com.github.jing332.tts.speech.plugin

import android.content.Context
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.tts.speech.EngineState
import com.github.jing332.tts.speech.TextToSpeechProvider
import com.github.jing332.tts.speech.plugin.engine.TtsPluginEngineV2
import com.github.jing332.tts.speech.plugin.engine.TtsPluginEngineV3
import com.github.jing332.tts.synthesizer.SystemParams
import java.io.InputStream

open class PluginTtsProvider(
    val context: Context,
    val plugin: Plugin,
) : TextToSpeechProvider<PluginTtsSource>() {

    // V2 引擎 (Rhino) - 兼容老插件
    private var mEngine: TtsPluginEngineV2? = null
    
    // V3 引擎 (QuickJS) - 支持新语法
    private var mEngineV3: TtsPluginEngineV3? = null

    // 保持对旧代码的兼容性访问
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

        // 修正：增加异常捕获与状态重置，确保在断网后能自动触发重连自愈
        return try {
            // 优先判断是否使用了 V3 引擎
            if (mEngineV3 != null) {
                mEngineV3!!.getAudio(
                    text = params.text,
                    locale = source.locale,
                    voice = source.voice,
                    rate = speed,
                    volume = volume,
                    pitch = pitch
                ) ?: throw IllegalStateException("QuickJS Engine returned null")
            } else {
                // 如果没有 V3，则回退到 V2 逻辑
                // V2 需要注入 source 上下文
                mEngine?.source = source
                
                mEngine?.getAudio(
                    text = params.text,
                    locale = source.locale,
                    voice = source.voice,
                    rate = speed,
                    volume = volume,
                    pitch = pitch
                ) ?: throw IllegalStateException("Engine not initialized: ${plugin.pluginId}")
            }
        } catch (e: Exception) {
            // 修正：发生网络或其他异常时，重置引擎状态为未初始化
            // 这将强制下一次请求重新执行 onInit()，从而实现网络恢复后的自愈
            state = EngineState.Uninitialized()
            throw e
        }
    }

    override suspend fun onInit() {
        state = EngineState.Initializing
        
        // 🛠️ 智能检测：如果插件代码里包含 "use quickjs" 字样，就启用 V3 引擎
        val useQuickJs = plugin.code.contains("\"use quickjs\"", ignoreCase = true) || 
                         plugin.code.contains("//@Env:QuickJS", ignoreCase = true)

        if (useQuickJs) {
            if (mEngineV3 == null) {
                mEngineV3 = TtsPluginEngineManager.getV3(context, plugin)
            }
        } else {
            // 否则默认使用 V2 (Rhino)
            if (mEngine == null) {
                mEngine = TtsPluginEngineManager.get(context, plugin)
            }
        }

        state = EngineState.Initialized
    }

    override fun onStop() {
        super.onStop()
        mEngine?.onStop()
        // V3 暂时不需要手动 Stop，由 Manager 缓存管理
    }

    override fun onDestroy() {
        state = EngineState.Uninitialized()
        mEngine?.onStop()
        mEngine = null
        mEngineV3 = null
    }
}
