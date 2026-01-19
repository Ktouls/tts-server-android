package com.github.jing332.tts

import android.content.Context
import com.github.jing332.database.entities.systts.source.TextToSpeechSource
import com.github.jing332.tts.speech.TextToSpeechProvider
import com.github.jing332.tts.util.AbstractCachedManager
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * 引擎缓存管理器：适配动态超时注入
 */
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
     * 获取引擎：增加 requestTimeout 参数注入
     */
    fun getEngine(
        context: Context, 
        source: TextToSpeechSource, 
        requestTimeout: Long // 🛡️ 注入外部超时配置
    ): TextToSpeechProvider<TextToSpeechSource>? {
        val key = source.getKey() + ";" + source.javaClass.simpleName

        val cachedEngine = cache[key]
        return if (cachedEngine == null) {
            // 🛡️ 转发参数给工厂类，确保超时设置下沉
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
