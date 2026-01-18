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
        
        // 🚀 预判特征库：只要代码里出现这些东西，铁定是现代 JS，直接切 QuickJS
        // 包含：const, let, async, await, 箭头函数(=>), 反引号模板字符串(`), class定义
        private val ES6_FEATURES = Pattern.compile(
            "\\b(const|let|async|await|class)\\b|=>|`", 
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
            // 路由分发：谁有值就用谁
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

        // 1. 第一层：显式暗号 (最高优先级)
        val hasTag = plugin.code.contains("\"use quickjs\"", ignoreCase = true)
        
        // 2. 第二层：特征扫描 (智能预判)
        // 只要扫描到 const/let 等新语法特征，就判定为需要 V3
        val hasEs6Features = ES6_FEATURES.matcher(plugin.code).find()

        if (hasTag || hasEs6Features) {
            // 命中！直接启动 QuickJS
            if (mEngineV3 == null) {
                Log.i(TAG, "检测到现代JS语法/标签，启用 QuickJS 引擎: ${plugin.name}")
                mEngineV3 = TtsPluginEngineManager.getV3(context, plugin)
            }
        } else {
            // 3. 兜底：看起来像老代码，尝试用 Rhino 启动
            // 为了防止漏网之鱼（正则没扫出来的漏网之鱼），这里保留一个“降级保护”
            try {
                if (mEngine == null) {
                    mEngine = TtsPluginEngineManager.get(context, plugin)
                }
            } catch (e: Exception) {
                // 如果 Rhino 还是挂了（说明正则漏判了），最后再试一次 QuickJS
                Log.w(TAG, "老引擎加载失败，降级尝试 QuickJS: ${e.message}")
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
