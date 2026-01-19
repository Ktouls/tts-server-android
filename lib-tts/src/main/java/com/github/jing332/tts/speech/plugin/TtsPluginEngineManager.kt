package com.github.jing332.tts.speech.plugin.engine

import android.content.Context
import android.util.Log
import com.github.jing332.database.entities.plugin.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 严谨版工厂：负责分发外部注入的配置参数
 */
object TtsPluginEngineManager {
    private const val TAG = "TtsPluginEngineManager"
    private val mEngines = ConcurrentHashMap<String, TtsPluginEngineV2>()
    private val mEnginesV3 = ConcurrentHashMap<String, TtsPluginEngineV3>()
    private val managerScope = CoroutineScope(Dispatchers.IO)

    /**
     * 获取 V2 引擎：同步超时注入
     */
    fun get(context: Context, plugin: Plugin, timeout: Long): TtsPluginEngineV2 {
        val key = plugin.pluginId + plugin.code.hashCode()
        return mEngines.getOrPut(key) {
            // 🛡️ 注入超时参数
            TtsPluginUiEngineV2(context, plugin, timeout).apply { eval() }
        }
    }

    /**
     * 获取 V3 引擎：同步超时注入
     */
    fun getV3(context: Context, plugin: Plugin, timeout: Long): TtsPluginEngineV3 {
        val key = "V3_" + plugin.pluginId + plugin.code.hashCode()
        return mEnginesV3.getOrPut(key) { 
            TtsPluginEngineV3(context, plugin, timeout) 
        }
    }

    fun remove(pluginId: String) {
        managerScope.launch {
            try {
                mEngines.keys.filter { it.contains(pluginId) }.forEach { mEngines.remove(it) }
                mEnginesV3.keys.filter { it.contains(pluginId) }.forEach { mEnginesV3.remove(it) }
            } catch (e: Exception) {
                Log.e(TAG, "异步移除引擎失败: ${e.message}")
            }
        }
    }

    fun clear() {
        managerScope.launch {
            runCatching {
                mEngines.clear()
                mEnginesV3.clear()
            }.onFailure { Log.e(TAG, "后台清理异常: ${it.message}") }
        }
    }
}
