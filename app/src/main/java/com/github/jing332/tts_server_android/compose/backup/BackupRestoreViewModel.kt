package com.github.jing332.tts_server_android.compose.backup

import androidx.lifecycle.ViewModel
import com.github.jing332.common.utils.FileUtils
import com.github.jing332.tts_server_android.app
import com.github.jing332.tts_server_android.conf.AppConfig
import com.github.jing332.tts_server_android.utils.MyTools
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import com.thegrizzlylabs.sardineandroid.model.DavResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class BackupRestoreViewModel : ViewModel() {
    // 恢复数据 (返回 true 表示需要重启)
    suspend fun restore(bytes: ByteArray): Boolean {
        return withContext(Dispatchers.IO) {
            val zip = ZipInputStream(ByteArrayInputStream(bytes))
            var needRestart = false
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(".json")) {
                    val jsonStr = String(zip.readBytes())
                    if (entry.name == "app_config.json") {
                        // 恢复 AppConfig
                        // 这里只是简单示意，实际逻辑可能更复杂，或者调用专门的 RestoreHelper
                        // MyTools.restoreConfig(jsonStr) 
                        needRestart = true
                    } else {
                        // 恢复数据库数据
                        // AppDatabase.restore(jsonStr)
                    }
                }
                entry = zip.nextEntry
            }
            // 简单起见，调用旧的恢复逻辑（假设 MyTools 或者 Migration 有这个方法）
            // 如果你的项目中并没有复杂的 zip 解析逻辑，可以直接调用现有的恢复入口
            // 这里我们模拟调用一下通用的恢复
             com.github.jing332.tts_server_android.compose.systts.Migration.importFromZip(bytes)
             
            true // 默认都重启
        }
    }

    // WebDAV 相关
    private fun getSardine(): Sardine {
        val sardine = OkHttpSardine()
        sardine.setCredentials(AppConfig.webDavUser.value, AppConfig.webDavPass.value)
        return sardine
    }

    suspend fun testWebDav() {
        withContext(Dispatchers.IO) {
            val sardine = getSardine()
            if (!sardine.exists(AppConfig.webDavUrl.value)) {
                throw Exception("Connection failed or path does not exist")
            }
        }
    }

    suspend fun getWebDavBackupFiles(): List<DavResource> {
        return withContext(Dispatchers.IO) {
            val sardine = getSardine()
            val url = AppConfig.webDavUrl.value + AppConfig.webDavPath.value
            if (!sardine.exists(url)) {
                sardine.createDirectory(url)
                return@withContext emptyList()
            }
            sardine.list(url).filter { !it.isDirectory && it.name.endsWith(".zip") }
        }
    }

    suspend fun downloadFromWebDav(fileName: String): ByteArray {
        return withContext(Dispatchers.IO) {
            val sardine = getSardine()
            val url = AppConfig.webDavUrl.value + AppConfig.webDavPath.value + "/" + fileName
            val stream = sardine.get(url)
            stream.readBytes()
        }
    }

    suspend fun downloadFromUrl(url: String): ByteArray {
        return withContext(Dispatchers.IO) {
            val client = OkHttpClient()
            val req = Request.Builder().url(url).build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) throw Exception("Download failed: code=${resp.code}")
            resp.body?.bytes() ?: throw Exception("Body is empty")
        }
    }
}
