package com.github.jing332.tts.speech.plugin.engine

import android.content.Context
import android.util.Log
import com.github.jing332.database.entities.plugin.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 严谨版工厂管理器：负责分发并注入外部传递的超时参数
 */
object TtsPluginEngineManager {
    private const val TAG = "TtsPluginEngineManager"
    
    // 使用并发容器，确保多线程环境下的内存安全
    private val mEngines = ConcurrentHashMap<String, TtsPluginEngineV2>()
    private val mEnginesV3 = ConcurrentHashMap<String, TtsPluginEngineV3>()
    
    // 专用于后台清理任务的 IO 协程作用域
    private val managerScope = CoroutineScope(Dispatchers.IO)

    /**
     * 获取或创建 V2 (Rhino) 引擎实例
     * @param timeout 由外部注入的动态超时设置
     */
    fun get(context: Context, plugin: Plugin, timeout: Long): TtsPluginEngineV2 {
        val key = plugin.pluginId + plugin.code.hashCode()
        return mEngines.getOrPut(key) {
            // 🛡️ 注入注入：将参数转发给 UiEngine
            TtsPluginUiEngineV2(context, plugin, timeout).apply { eval() }
        }
    }

    /**
     * 获取或创建 V3 (QuickJS) 引擎实例
     * @param timeout 由外部注入的动态超时设置
     */
    fun getV3(context: Context, plugin: Plugin, timeout: Long): TtsPluginEngineV3 {
        val key = "V3_" + plugin.pluginId + plugin.code.hashCode()
        return mEnginesV3.getOrPut(key) {
            // 🛡️ 注入注入：将参数转发给 V3 引擎
            TtsPluginEngineV3(context, plugin, timeout)
        }
    }

    /**
     * 异步释放特定插件的引擎资源
     */
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
     * 🛠️ 关键防御：完全脱离 UI 线程的异步清理逻辑
     * 确保点击菜单触发清理时，主线程秒回响应，杜绝白屏现象
     */
    fun clear() {
        Log.i(TAG, "触发异步清理所有引擎缓存...")
        managerScope.launch {
            runCatching {
                mEngines.clear()
                mEnginesV3.clear()
                Log.d(TAG, "后台清理引擎缓存成功")
            }.onFailure { 
                Log.e(TAG, "后台清理发生非致命异常: ${it.message}") 
            }
        }
    }
}
