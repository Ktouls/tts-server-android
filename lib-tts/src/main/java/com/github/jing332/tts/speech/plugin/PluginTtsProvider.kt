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
        
        // 🚀 修正后的正则：只检测 async 和 await
        // 移除对反引号(`)和箭头函数(=>)的检测，因为 Rhino 其实支持它们，防止误判 Azure 插件
        private val ASYNC_FEATURES = Pattern.compile(
            "\\b(async|await)\\b", 
            Pattern.CASE_INSENSITIVE
        )
    }

    private var mEngine: TtsPluginEngineV2? = null
    private var mEngineV3: TtsPluginEngineV3? = null

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

        // 1. 显式标记 (最高优先级)
        val hasTag = plugin.code.contains("\"use quickjs\"", ignoreCase = true)
        
        // 2. 异步特征检测 (最保守策略)
        // 只有代码里写了 async 或 await，才认为是 V3 插件
        val hasAsync = ASYNC_FEATURES.matcher(plugin.code).find()

        if (hasTag || hasAsync) {
            Log.i(TAG, "检测到 async/await 或标签，启用 QuickJS: ${plugin.name}")
            if (mEngineV3 == null) {
                mEngineV3 = TtsPluginEngineManager.getV3(context, plugin)
            }
        } else {
            // 3. 其他情况全部默认走 Rhino (兼容 Azure 等旧插件)
            try {
                if (mEngine == null) {
                    mEngine = TtsPluginEngineManager.get(context, plugin)
                }
            } catch (e: Exception) {
                // 兜底：万一 Rhino 真的崩了，再试一次 QuickJS
                Log.w(TAG, "V2 引擎加载失败，尝试 QuickJS 救场: ${e.message}")
                mEngine = null
                if (mEngineV3 == null) {
                    mEngineV3 = TtsPluginEngineManager.getV3(context, plugin)
                }
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
