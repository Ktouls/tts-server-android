package com.github.jing332.tts

import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.database.entities.systts.source.TextToSpeechSource
import com.github.jing332.tts.synthesizer.ITtsRequester
import com.github.jing332.tts.synthesizer.RequestPayload
import kotlinx.coroutines.TimeoutCancellationException
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * 严谨版请求器：适配注入式超时与网络错误暗号
 */
class DefaultTtsRequester(val context: SynthesizerContext) : ITtsRequester {
    override suspend fun request(payload: RequestPayload): InputStream {
        val source = payload.config.source
        val params = payload.params
        
        // 🛡️ 注入注入：从上下文读取由 App 注入的动态超时
        val timeoutMs = context.cfg.requestTimeout()

        return if (source is PluginTtsSource) {
            // 🛡️ 适配第 8 步修改后的签名
            val engine = CachedEngineManager.getEngine(context.androidContext, source, timeoutMs)
                ?: throw IllegalStateException("Plugin engine initialized failed: ${source.pluginId}")
            
            try {
                engine.getAudio(
                    params.text, source.locale, source.voice,
                    payload.config.speechInfo.speed,
                    payload.config.speechInfo.volume,
                    payload.config.speechInfo.pitch
                ) ?: throw IllegalStateException("Engine returned null stream")
            } catch (e: TimeoutCancellationException) {
                // 🛠️ 配合 SystemTtsService：抛出超时暗号，解决系统转圈问题
                val errMark = "TTS_NET_ERR: Request Timeout (${timeoutMs}ms)".toByteArray()
                ByteArrayInputStream(errMark)
            }
        } else {
            // 非插件引擎逻辑（如本地 TTS）保持原有流程
            val engine = CachedEngineManager.getEngine(context.androidContext, source, timeoutMs)
                ?: throw IllegalStateException("Engine initialized failed")
            engine.getStream(params, source)
        }
    }
}
