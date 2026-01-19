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
 * 严谨版请求器：适配接口定义与错误类型
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
            // 3. 参数同步：将配置中的动态参数应用到 source 对象，确保引擎能读取到最新的语速/音量
            // 注意：这里假设 source 是可变对象，若 source 是只读的，getStream 内部可能只会读取默认值
            // 但在当前的架构规约下，这是将 audioParams 传递给 getStream 的唯一标准途径
            if (source is PluginTtsSource) {
                source.speed = (tts.audioParams.speed * 50).toInt() // 假设映射关系：50为基准
                source.volume = (tts.audioParams.volume * 50).toInt()
                source.pitch = (tts.audioParams.pitch * 50).toInt()
            }
            // 若是非插件源，通常有其特定的参数设置逻辑，这里保持通用调用

            // 4. 执行请求：使用标准的 getStream 接口
            val stream = try {
                engine.getStream(params, source)
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
            // 5. 异常封装：使用 RequesterError.RequestError 包装异常
            Err(RequesterError.RequestError(e))
        }
    }

    override fun destroy() {
        // 清理逻辑
    }
}
