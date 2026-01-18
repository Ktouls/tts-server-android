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
        
        // 🚀 修正后的特征库：移除 let/const，避免误伤旧插件
        // 只有出现 async, await, 箭头函数(=>), class, 或者显式标记时才启用 V3
        private val ES6_FEATURES = Pattern.compile(
            "\\b(async|await|class)\\b|=>|`", 
            Pattern.CASE_INSENSITIVE
        )
    }

    private var mEngine: TtsPluginEngineV2? = null
    private var mEngineV3: TtsPluginEngineV3? = null

    // 保持兼容
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
                // V2 回退逻辑
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

        // 1. 最高优先级：显式暗号 "use quickjs"
        val hasTag = plugin.code.contains("\"use quickjs\"", ignoreCase = true)
        
        // 2. 次级优先级：检测旧引擎绝对不支持的语法 (Async/Await/箭头函数)
        // 注意：这里去掉了对 let/const 的检测，因为 Rhino 也支持它们，防止误判
        val hasEs6Features = ES6_FEATURES.matcher(plugin.code).find()

        if (hasTag || hasEs6Features) {
            Log.i(TAG, "检测到现代JS语法，启用 QuickJS 引擎: ${plugin.name}")
            if (mEngineV3 == null) {
                mEngineV3 = TtsPluginEngineManager.getV3(context, plugin)
            }
        } else {
            // 3. 默认走老引擎 (Rhino)
            try {
                if (mEngine == null) {
                    mEngine = TtsPluginEngineManager.get(context, plugin)
                }
            } catch (e: Exception) {
                // 兜底：如果老引擎实在跑不起来，再试试新引擎
                Log.w(TAG, "V2 引擎加载失败，尝试 V3: ${e.message}")
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
