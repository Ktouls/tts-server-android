package com.github.jing332.tts

import com.github.jing332.tts.synthesizer.AbstractMixSynthesizer
import com.github.jing332.tts.synthesizer.IBgmPlayer
import com.github.jing332.tts.synthesizer.IResultProcessor
import com.github.jing332.tts.synthesizer.ITextProcessor
import com.github.jing332.tts.synthesizer.ITtsRepository
import com.github.jing332.tts.synthesizer.ITtsRequester
import io.github.oshai.kotlinlogging.KotlinLogging
import splitties.init.appCtx

/**
 * 混合合成器：严谨适配注入式上下文，确保超时逻辑全链路透传
 */
open class MixSynthesizer(
    final override val context: SynthesizerContext
) : AbstractMixSynthesizer() {
    // 🛡️ 严谨：所有组件共享同一个 Context，确保注入的配置在全链路生效
    override var textProcessor: ITextProcessor = TextProcessor(context)
    override var ttsRequester: ITtsRequester = DefaultTtsRequester(context)
    override var streamProcessor: IResultProcessor = DefaultResultProcessor(context)
    override var repo: ITtsRepository = TtsRepository(context)
    override var bgmPlayer: IBgmPlayer = BgmPlayer(context)

    companion object {
        val global by lazy {
            val logger = KotlinLogging.logger("TtsManager")
            MixSynthesizer(
                SynthesizerContext(
                    androidContext = appCtx,
                    logger = logger,
                    // 默认防御值，实际运行时会被 SystemTtsService 注入的配置覆盖
                    cfg = SynthesizerConfig(requestTimeout = { 5000L }) 
                )
            )
        }
    }
}
