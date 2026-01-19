package com.github.jing332.tts.speech.plugin.engine

import android.content.Context
import android.util.Log
import com.github.jing332.database.entities.plugin.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 严谨版管理器：通过协程异步清理和 ConcurrentHashMap 彻底杜绝 UI 死锁（白屏）
 */
object TtsPluginEngineManager {
    private const val TAG = "TtsPluginEngineManager"
    
    // 使用并发容器，确保多线程操作安全
    private val mEngines = ConcurrentHashMap<String, TtsPluginEngineV2>()
    private val mEnginesV3 = ConcurrentHashMap<String, TtsPluginEngineV3>()
    
    // 专用于后台管理任务的协程作用域
    private val managerScope = CoroutineScope(Dispatchers.IO)

    fun get(context: Context, plugin: Plugin): TtsPluginEngineV2 {
        val key = plugin.pluginId + plugin.code.hashCode()
        return mEngines.getOrPut(key) {
            TtsPluginUiEngineV2(context, plugin).apply { eval() }
        }
    }

    fun getV3(context: Context, plugin: Plugin): TtsPluginEngineV3 {
        val key = "V3_" + plugin.pluginId + plugin.code.hashCode()
        return mEnginesV3.getOrPut(key) {
            TtsPluginEngineV3(context, plugin)
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

    /**
     * 🛠️ 核心修复：点击菜单触发的清理逻辑必须完全脱离主线程
     */
    fun clear() {
        Log.i(TAG, "触发异步清理所有引擎缓存...")
        managerScope.launch {
            runCatching {
                mEngines.clear()
                mEnginesV3.clear()
                Log.d(TAG, "后台引擎缓存清理完成")
            }.onFailure { 
                Log.e(TAG, "后台清理异常: ${it.message}") 
            }
        }
    }
}
