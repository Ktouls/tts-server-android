package com.github.jing332.tts

import android.content.Context
import com.github.jing332.database.dbm
import com.github.jing332.database.entities.systts.source.TextToSpeechSource
import com.github.jing332.database.entities.systts.source.LocalTtsSource
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.tts.speech.TextToSpeechProvider
import com.github.jing332.tts.speech.local.LocalTtsProvider
import com.github.jing332.tts.speech.plugin.PluginTtsProvider

@Suppress("UNCHECKED_CAST")
object SpeechServiceFactory {
    /**
     * 🛠️ 严谨适配：新增 requestTimeout 参数，实现跨模块配置注入
     */
    fun createEngine(
        context: Context, 
        source: TextToSpeechSource, 
        requestTimeout: Long // 🛡️ 注入外部超时配置
    ): TextToSpeechProvider<TextToSpeechSource>? {
        return when (source) {
            is LocalTtsSource -> LocalTtsProvider(context, source.engine)
            is PluginTtsSource -> {
                // 🛡️ 寻找插件实体，并注入超时参数
                PluginTtsProvider(
                    context,
                    source.plugin ?: dbm.pluginDao.getEnabled(source.pluginId) ?: return null,
                    requestTimeout
                )
            }

            else -> null
        } as TextToSpeechProvider<TextToSpeechSource>?
    }
}
