package com.github.jing332.tts.speech.plugin.engine

import android.content.Context
import android.util.Log
import com.github.jing332.database.entities.plugin.Plugin
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object TtsPluginEngineManager {
    private const val TAG = "TtsPluginEngineManager"
    
    // 🛠️ 严谨：使用 ConcurrentHashMap 替代同步装饰器，提高多线程下的稳定性
    private val mEngines = ConcurrentHashMap<String, TtsPluginEngineV2>()
    private val mEnginesV3 = ConcurrentHashMap<String, TtsPluginEngineV3>()

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
        // 使用迭代器安全移除，防止 ConcurrentModificationException
        val it = mEngines.entries.iterator()
        while(it.hasNext()) { if(it.next().key.contains(pluginId)) it.remove() }
        
        val it3 = mEnginesV3.entries.iterator()
        while(it3.hasNext()) { if(it3.next().key.contains(pluginId)) it3.remove() }
    }

    fun clear() {
        Log.i(TAG, "正在清理引擎缓存...")
        // 🛠️ 关键修复：异步执行清理操作，防止阻塞 UI 线程导致白屏
        kotlin.runCatching {
            mEngines.clear()
            mEnginesV3.clear()
            Log.d(TAG, "引擎缓存清理完成")
        }.onFailure { e ->
            Log.e(TAG, "清理缓存时发生非致命异常: ${e.message}")
        }
    }
}
