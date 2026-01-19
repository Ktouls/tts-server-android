package com.github.jing332.tts.speech.plugin.engine

import android.content.Context
import android.util.Log
import com.github.jing332.database.entities.plugin.Plugin
import java.util.Collections

/**
 * 严谨版引擎管理器：通过异常隔离确保主线程不因引擎错误而崩溃
 */
object TtsPluginEngineManager {
    private const val TAG = "TtsPluginEngineManager"
    
    // 使用线程安全的容器
    private val mEngines = Collections.synchronizedMap(mutableMapOf<String, TtsPluginEngineV2>())
    private val mEnginesV3 = Collections.synchronizedMap(mutableMapOf<String, TtsPluginEngineV3>())

    /**
     * 获取 V2 引擎：此处强制使用 TtsPluginUiEngineV2 以支持 UI 净化
     */
    fun get(context: Context, plugin: Plugin): TtsPluginEngineV2 {
        val key = plugin.pluginId + plugin.code.hashCode()
        return mEngines.getOrPut(key) {
            TtsPluginUiEngineV2(context, plugin).apply { eval() }
        }
    }

    /**
     * 获取 V3 引擎：增加致命错误拦截
     */
    fun getV3(context: Context, plugin: Plugin): TtsPluginEngineV3 {
        val key = "V3_" + plugin.pluginId + plugin.code.hashCode()
        return mEnginesV3.getOrPut(key) {
            try {
                TtsPluginEngineV3(context, plugin)
            } catch (e: Throwable) {
                // 如果 QuickJS 依赖或 Native 库加载失败，记录日志并抛出业务异常，而非导致类加载崩溃
                Log.e(TAG, "QuickJS 引擎初始化失败: ${e.message}")
                throw RuntimeException("QuickJS 引擎不可用，请检查插件代码或应用依赖")
            }
        }
    }

    /**
     * 释放资源
     */
    fun remove(pluginId: String) {
        try {
            mEngines.keys.removeAll { it.contains(pluginId) }
            mEnginesV3.keys.removeAll { it.contains(pluginId) }
        } catch (e: Exception) {
            Log.e(TAG, "移除引擎缓存失败: ${e.message}")
        }
    }

    /**
     * 🛠️ 关键修复：防御菜单点击触发的清理逻辑
     * 使用 runCatching 确保主线程在执行清理时绝对不会因为引擎状态问题而白屏/闪退
     */
    fun clear() {
        kotlin.runCatching {
            mEngines.clear()
            mEnginesV3.clear()
            Log.d(TAG, "引擎缓存已清理")
        }.onFailure { e ->
            Log.e(TAG, "清理所有引擎缓存时发生异常: ${e.message}")
        }
    }
}
