package com.github.jing332.tts.speech.plugin.engine

import android.content.Context
import com.github.jing332.database.entities.plugin.Plugin
import java.util.Collections

/**
 * 严谨版引擎管理器：支持 V2 (Rhino) 与 V3 (QuickJS) 实例管理
 */
object TtsPluginEngineManager {
    // 使用线程安全的 Map 缓存引擎实例
    private val mEngines = Collections.synchronizedMap(mutableMapOf<String, TtsPluginEngineV2>())
    private val mEnginesV3 = Collections.synchronizedMap(mutableMapOf<String, TtsPluginEngineV3>())

    /**
     * 获取或创建 V2 (Rhino) 引擎实例
     */
    fun get(context: Context, plugin: Plugin): TtsPluginEngineV2 {
        val key = plugin.pluginId + plugin.code.hashCode()
        return mEngines.getOrPut(key) {
            TtsPluginEngineV2(context, plugin).apply { eval() }
        }
    }

    /**
     * 🛠️ 新增：获取或创建 V3 (QuickJS) 引擎实例
     * 严谨性：独立缓存，确保 V3 环境与 V2 互不干扰
     */
    fun getV3(context: Context, plugin: Plugin): TtsPluginEngineV3 {
        // 使用代码哈希作为 Key，确保脚本更新时能即时重载引擎
        val key = "V3_" + plugin.pluginId + plugin.code.hashCode()
        return mEnginesV3.getOrPut(key) {
            // V3 引擎在 Provider.onInit 中会被初始化
            TtsPluginEngineV3(context, plugin)
        }
    }

    /**
     * 释放特定插件的所有引擎资源
     */
    fun remove(pluginId: String) {
        mEngines.keys.removeAll { it.startsWith(pluginId) }
        mEnginesV3.keys.removeAll { it.contains(pluginId) }
    }

    /**
     * 清空所有引擎缓存
     */
    fun clear() {
        mEngines.clear()
        mEnginesV3.clear()
    }
}
