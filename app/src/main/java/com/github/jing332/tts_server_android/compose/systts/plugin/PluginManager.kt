package com.github.jing332.tts_server_android.compose.systts.plugin

import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.tts_server_android.constant.AppConst
import splitties.init.appCtx
import java.io.File

class PluginManager(private val plugin: Plugin) {
    // 👇👇👇 新的存储路径：FilesDir/plugin_cache (不会被系统自动清理)
    private val cacheDir = File(appCtx.getExternalFilesDir("plugin_cache"), plugin.pluginId)

    // 👇👇👇 旧的存储路径：ExternalCacheDir (用于清理残留)
    private val legacyCacheDir = File(AppConst.externalCacheDir.absolutePath + "/${plugin.pluginId}")

    fun hasCache(): Boolean {
        return try {
            // 只要新目录或旧目录有文件，就认为有缓存
            (cacheDir.list()?.isNotEmpty() == true) || (legacyCacheDir.list()?.isNotEmpty() == true)
        } catch (e: Exception) {
            false
        }
    }

    fun clearCache() {
        try {
            // 清理新路径
            cacheDir.deleteRecursively()
            // 同时也清理旧路径，防止垃圾残留
            legacyCacheDir.deleteRecursively()
        } catch (_: Exception) {
        }
    }
}
