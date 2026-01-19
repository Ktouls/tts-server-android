package com.github.jing332.tts

import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.tts.error.RequesterError
import com.github.jing332.tts.synthesizer.ITtsRequester
import com.github.jing332.tts.synthesizer.SystemParams
import com.github.jing332.tts.synthesizer.TtsConfiguration
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.TimeoutCancellationException
import java.io.ByteArrayInputStream

/**
 * 严谨版请求器：完全对齐 ITtsRequester 接口签名
 * 实现了动态超时注入与网络错误暗号回传机制
 */
class DefaultTtsRequester(val context: SynthesizerContext) : ITtsRequester {
    
    override suspend fun request(
        params: SystemParams,
        tts: TtsConfiguration
    ): Result<ITtsRequester.Response, RequesterError> {
        val source = tts.source
        
        // 🛡️ 注入：从上下文读取由 App 注入的动态超时设置
        val timeoutMs = context.cfg.requestTimeout()

        // 获取引擎实例，适配第 8 步修改后的签名
        val engine = CachedEngineManager.getEngine(context.androidContext, source, timeoutMs)
            ?: return Err(RequesterError.Unknown("Engine initialized failed"))

        return try {
            if (source is PluginTtsSource) {
                val inputStream = try {
                    // 🛠️ 修正：通过 tts.speechInfo 读取语速、音量、音高
                    engine.getAudio(
                        params.text, source.locale, source.voice,
                        tts.speechInfo.speed,
                        tts.speechInfo.volume,
                        tts.speechInfo.pitch
                    ) ?: return Err(RequesterError.EmptyResponse)
                } catch (e: TimeoutCancellationException) {
                    // 🛠️ 注入暗号：配合 SystemTtsService 捕获，解决系统转圈死锁
                    val errMark = "TTS_NET_ERR: Request Timeout (${timeoutMs}ms)".toByteArray()
                    ByteArrayInputStream(errMark)
                }
                
                Ok(ITtsRequester.Response(stream = inputStream))
            } else {
                // 非插件引擎（内置/系统 TTS）逻辑
                val stream = engine.getStream(params, source)
                Ok(ITtsRequester.Response(stream = stream))
            }
        } catch (e: Exception) {
            // 防御性编程：捕获合成过程中的意外异常
            Err(RequesterError.Unknown(e.message ?: e.toString()))
        }
    }

    override fun destroy() {
        // 预留清理逻辑
    }
}
