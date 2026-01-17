package com.github.jing332.tts

import com.github.jing332.tts.error.RequesterError
import com.github.jing332.tts.synthesizer.ITtsRequester
import com.github.jing332.tts.synthesizer.SystemParams
import com.github.jing332.tts.synthesizer.TtsConfiguration
import com.github.jing332.tts.speech.EngineState
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout

class DefaultTtsRequester(
    var context: SynthesizerContext,
) : ITtsRequester {
    override suspend fun request(
        params: SystemParams, tts: TtsConfiguration,
    ): Result<ITtsRequester.Response, RequesterError> {
        val engine =
            CachedEngineManager.getEngine(context.androidContext, tts.source) ?: return Err(
                RequesterError.StateError("engine ${tts.source} not found")
            )

        // 修正：增加异常捕获，防止引擎初始化崩溃
        if (engine.state != EngineState.Initialized) {
            try {
                engine.onInit()
            } catch (e: Exception) {
                return Err(RequesterError.RequestError(e))
            }
        }

        return if (engine.isSyncPlay(tts.source)) {
            Ok(
                ITtsRequester.Response(
                    callback = ITtsRequester.ISyncPlayCallback {
                        engine.syncPlay(params, tts.source)
                    }
                )
            )
        } else {
            try {
                // 修正：增加强制超时保护，时长由配置决定，防止断网导致请求挂死
                val timeout = (context.cfg.requestTimeout() ?: 10000).toLong()
                withTimeout(timeout) {
                    Ok(
                        ITtsRequester.Response(stream = engine.getStream(params, tts.source))
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 修正：如果网络请求彻底失败，销毁当前引擎状态。
                // 这样网络恢复后，下次请求会重新触发 onInit() 建立连接，实现自愈。
                engine.onDestroy() 
                Err(RequesterError.RequestError(e))
            }
        }
    }

    override fun destroy() {
    }
}
