package com.github.jing332.tts.speech.plugin

import android.content.Context
import android.util.Log
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
        // 修复：新建插件没有ID，防止 eval 崩溃导致无法保存
        if (plugin.pluginId.isEmpty()) {
            val engine = TtsPluginUiEngineV2(context, plugin)
            try {
                engine.eval()
            } catch (e: Exception) {
                // 吞掉错误：新建插件时可能因为缺少ID导致 eval 里的日志打印报错
                // 我们捕获它，确保界面不会闪退，让用户能保存成功。
                Log.w("TtsPluginEngineManager", "新建插件预加载验证跳过: ${e.message}")
            }
            return engine
        }

        return cache.get(plugin.pluginId) ?: run {
            val engine = TtsPluginUiEngineV2(context, plugin)
            engine.eval()
            cache.put(plugin.pluginId, engine)
            engine
        }
    }

    private val v3CacheMap = mutableMapOf<String, TtsPluginEngineV3>()

    /**
     * 获取 V3 引擎 (QuickJS)
     */
    fun getV3(context: Context, plugin: Plugin): TtsPluginEngineV3 {
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
