package com.github.jing332.tts

import android.content.Context
import com.github.jing332.database.entities.systts.source.TextToSpeechSource
import com.github.jing332.tts.speech.TextToSpeechProvider
import com.github.jing332.tts.util.AbstractCachedManager
import io.github.oshai.kotlinlogging.KotlinLogging

object CachedEngineManager :
    AbstractCachedManager<String, TextToSpeechProvider<TextToSpeechSource>>(
        timeout = 1000L * 60L * 10L, // 10 min
        delay = 1000L * 60 // 1 min
    ) {
    private val logger = KotlinLogging.logger("CachedEngineManager")

    override fun onCacheRemove(key: String, value: TextToSpeechProvider<TextToSpeechSource>): Boolean {
        logger.atDebug { message = "Engine timeout destroy: $key" }
        value.onDestroy()

        return super.onCacheRemove(key, value)
    }

    /**
     * 🛡️ 注入式适配：接收外部传入的 requestTimeout
     */
    fun getEngine(
        context: Context, 
        source: TextToSpeechSource, 
        requestTimeout: Long // 🛡️ 注入外部超时配置
    ): TextToSpeechProvider<TextToSpeechSource>? {
        val key = source.getKey() + ";" + source.javaClass.simpleName

        val cachedEngine = cache[key]
        return if (cachedEngine == null) {
            // 🛡️ 转发给工厂类
            val engine = SpeechServiceFactory.createEngine(context, source, requestTimeout) ?: return null
            cache.put(key, engine)
            engine
        } else {
            cachedEngine
        }
    }

    fun expireAll() {
        logger.atDebug { message = "Expire all cached engine" }
        cache.removeAll {
            it.onDestroy()
            true
        }
    }
}
