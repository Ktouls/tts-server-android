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
     */
    fun get(context: Context, plugin: Plugin): TtsPluginUiEngineV2 {
        // 修复：如果插件没有ID（新建状态），直接返回新实例，不走缓存，防止空指针
        if (plugin.pluginId.isEmpty()) {
            return TtsPluginUiEngineV2(context, plugin).apply { eval() }
        }

        return cache.get(plugin.pluginId) ?: run {
            val engine = TtsPluginUiEngineV2(context, plugin)
            engine.eval()
            cache.put(plugin.pluginId, engine)
            engine
        }
    }

    // V3 引擎缓存池
    private val v3CacheMap = mutableMapOf<String, TtsPluginEngineV3>()

    /**
     * 获取 V3 引擎 (QuickJS)
     */
    fun getV3(context: Context, plugin: Plugin): TtsPluginEngineV3 {
        // 修复：同上，无ID时不缓存
        if (plugin.pluginId.isEmpty()) {
            return TtsPluginEngineV3(context, plugin)
        }

        return v3CacheMap[plugin.pluginId] ?: run {
            val engine = TtsPluginEngineV3(context, plugin)
            v3CacheMap[plugin.pluginId] = engine
            engine
        }
    }
}
