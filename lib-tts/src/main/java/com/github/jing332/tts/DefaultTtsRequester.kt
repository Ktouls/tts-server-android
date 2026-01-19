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
 * 严谨版请求器：修复不可变对象修改与类型匹配问题
 */
class DefaultTtsRequester(val context: SynthesizerContext) : ITtsRequester {

    override suspend fun request(
        params: SystemParams,
        tts: TtsConfiguration
    ): Result<ITtsRequester.Response, RequesterError> {
        val source = tts.source
        
        // 1. 注入：读取系统动态超时
        val timeoutMs = context.cfg.requestTimeout()

        // 2. 初始化引擎
        val engine = CachedEngineManager.getEngine(context.androidContext, source, timeoutMs)
            ?: return Err(RequesterError.StateError("Engine initialized failed"))

        return try {
            // 3. 参数同步：创建 Source 副本以应用动态参数
            // 修复点：使用 .copy() 处理不可变对象，且直接传递 Float 类型
            val finalSource = if (source is PluginTtsSource) {
                source.copy(
                    speed = tts.audioParams.speed,
                    volume = tts.audioParams.volume,
                    pitch = tts.audioParams.pitch
                )
            } else {
                source
            }

            // 4. 执行请求：使用标准的 getStream 接口
            val stream = try {
                engine.getStream(params, finalSource)
            } catch (e: TimeoutCancellationException) {
                // 🛡️ 捕获超时：返回暗号流，触发 SystemTtsService 的防御机制
                val errMark = "TTS_NET_ERR: Request Timeout (${timeoutMs}ms)".toByteArray()
                ByteArrayInputStream(errMark)
            }

            if (stream == null) {
                Err(RequesterError.StateError("Engine returned null stream"))
            } else {
                Ok(ITtsRequester.Response(stream = stream))
            }
        } catch (e: Exception) {
            // 5. 异常封装
            Err(RequesterError.RequestError(e))
        }
    }

    override fun destroy() {
        // 清理逻辑
    }
}
