package com.github.jing332.tts.speech.plugin

import android.content.Context
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.tts.speech.plugin.engine.TtsPluginEngineV3
import com.github.jing332.tts.speech.plugin.engine.TtsPluginUiEngineV2
import com.github.jing332.tts.util.AbstractCachedManager

object TtsPluginEngineManager : AbstractCachedManager<String, TtsPluginUiEngineV2>(
    timeout = 1000L * 60L * 10L, // 10 min
    delay = 1000L * 60L * 1L, // 1 min
) {
    /**
     * 获取 V2 引擎 (Rhino)
     * 用于兼容旧插件和 UI 界面变量获取
     */
    fun get(context: Context, plugin: Plugin): TtsPluginUiEngineV2 {
        return cache.get(plugin.pluginId) ?: run {
            val engine = TtsPluginUiEngineV2(context, plugin)
            engine.eval()
            cache.put(plugin.pluginId, engine)
            engine
        }
    }

    // 👇👇👇 新增：V3 引擎缓存池 👇👇👇
    private val v3CacheMap = mutableMapOf<String, TtsPluginEngineV3>()

    /**
     * 获取 V3 引擎 (QuickJS)
     * 用于执行支持 ES2020+ 的新插件
     */
    fun getV3(context: Context, plugin: Plugin): TtsPluginEngineV3 {
        // 简单的缓存机制，避免重复创建 OkHttpClient
        return v3CacheMap[plugin.pluginId] ?: run {
            val engine = TtsPluginEngineV3(context, plugin)
            v3CacheMap[plugin.pluginId] = engine
            engine
        }
    }
}
