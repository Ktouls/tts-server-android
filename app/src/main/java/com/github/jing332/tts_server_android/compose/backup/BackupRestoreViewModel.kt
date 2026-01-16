package com.github.jing332.tts_server_android.compose.backup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.drake.net.Net
import com.drake.net.utils.withIO
import com.github.jing332.common.utils.FileUtils
import com.github.jing332.common.utils.ZipUtils
import com.github.jing332.database.dbm
import com.github.jing332.database.entities.SpeechRule
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.database.entities.replace.GroupWithReplaceRule
import com.github.jing332.database.entities.systts.GroupWithSystemTts
import com.github.jing332.tts_server_android.conf.AppConfig
import com.github.jing332.tts_server_android.constant.AppConst
import com.thegrizzlylabs.sardineandroid.SardineFactory
import com.thegrizzlylabs.sardineandroid.model.DavResource
import kotlinx.serialization.encodeToString
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

class BackupRestoreViewModel(application: Application) : AndroidViewModel(application) {
    // ... /cache/backupRestore
    private val backupRestorePath by lazy {
        application.externalCacheDir!!.absolutePath + File.separator + "backupRestore"
    }

    // /data/data/{package name}
    private val internalDataFile by lazy {
        application.filesDir!!.parentFile!!
    }

    // ... /cache/backupRestore/restore
    private val restorePath by lazy {
        backupRestorePath + File.separator + "restore"
    }

    // ... /cache/backupRestore/restore/shared_prefs
    private val restorePrefsPath by lazy {
        restorePath + File.separator + "shared_prefs"
    }

    // WebDAV 相关配置
    private fun getSardine() = SardineFactory.begin(AppConfig.webDavUser.value, AppConfig.webDavPass.value)
    private fun getWebDavUrl(path: String = ""): String {
        val baseUrl = AppConfig.webDavUrl.value.trimEnd('/')
        val targetPath = path.trimStart('/')
        return "$baseUrl/$targetPath"
    }

    // 测试 WebDAV 连接
    suspend fun testWebDav(): Boolean = withIO {
        try {
            getSardine().list(getWebDavUrl())
            true
        } catch (e: Exception) {
            throw e
        }
    }

    // 获取 WebDAV 备份文件列表
    suspend fun getWebDavBackupFiles(): List<DavResource> = withIO {
        val sardine = getSardine()
        val path = AppConfig.webDavPath.value
        val url = getWebDavUrl(path)

        try {
            if (!sardine.exists(url)) {
                return@withIO emptyList()
            }
            sardine.list(url).filter {
                !it.isDirectory && it.name.endsWith(".zip", ignoreCase = true)
            }
        } catch (e: Exception) {
            throw e
        }
    }

    // 从 WebDAV 下载文件
    suspend fun downloadFromWebDav(fileName: String): ByteArray = withIO {
        val sardine = getSardine()
        val path = AppConfig.webDavPath.value
        val url = getWebDavUrl("$path/$fileName")
        sardine.get(url).readBytes()
    }

    // 上传到 WebDAV
    suspend fun uploadToWebDav(data: ByteArray, fileName: String) = withIO {
        val sardine = getSardine()
        val path = AppConfig.webDavPath.value
        val url = getWebDavUrl(path)
        val fileUrl = getWebDavUrl("$path/$fileName")

        if (!sardine.exists(url)) {
            sardine.createDirectory(url)
        }
        sardine.put(fileUrl, data)
    }

    // 从 URL 下载直链文件
    suspend fun downloadFromUrl(url: String): ByteArray = withIO {
        Net.get(url).execute<ByteArray>()
    }

    suspend fun restore(bytes: ByteArray): Boolean {
        var isRestart = false
        val outFileDir = File(restorePath)
        outFileDir.deleteRecursively() // 先清理旧的
        outFileDir.mkdirs()

        ZipUtils.unzipFile(ZipInputStream(ByteArrayInputStream(bytes)), outFileDir)
        if (outFileDir.exists()) {
            // shared_prefs
            val restorePrefsFile = File(restorePrefsPath)
            if (restorePrefsFile.exists()) {
                FileUtils.copyFolder(restorePrefsFile, internalDataFile)
                restorePrefsFile.deleteRecursively()
                isRestart = true
            }

            // *.json
            val files = outFileDir.listFiles()
            if (files != null) {
                for (file in files) {
                    if (file.isFile) importFromJsonFile(file)
                }
            }
        }

        return isRestart
    }

    private fun importFromJsonFile(file: File) {
        val jsonStr = file.readText()
        if (file.name.endsWith("list.json")) {
            val list: List<GroupWithSystemTts> = AppConst.jsonBuilder.decodeFromString(jsonStr)
            dbm.systemTtsV2.insertGroupWithTts(*list.toTypedArray())
        } else if (file.name.endsWith("speechRules.json")) {
            val list: List<SpeechRule> = AppConst.jsonBuilder.decodeFromString(jsonStr)
            dbm.speechRuleDao.insertOrUpdate(*list.toTypedArray())
        } else if (file.name.endsWith("replaceRules.json")) {
            val list: List<GroupWithReplaceRule> =
                AppConst.jsonBuilder.decodeFromString(jsonStr)
            dbm.replaceRuleDao.insertRuleWithGroup(*list.toTypedArray())
        } else if (file.name.endsWith("plugins.json")) {
            val list: List<Plugin> = AppConst.jsonBuilder.decodeFromString(jsonStr)
            dbm.pluginDao.insertOrUpdate(*list.toTypedArray())
        }
    }

    suspend fun backup(_types: List<Type>): ByteArray = withIO {
        File(tmpZipPath).deleteRecursively()
        File(tmpZipPath).mkdirs()

        val types = _types.toMutableList()
        if (types.contains(Type.PluginVars)) types.remove(Type.Plugin)
        types.forEach {
            createConfigFile(it)
        }

        val zipFile = File(tmpZipFile)
        ZipUtils.zipFolder(File(tmpZipPath), zipFile)
        return@withIO zipFile.readBytes()
    }

    override fun onCleared() {
        super.onCleared()
        File(backupRestorePath).deleteRecursively()
    }

    // ... /cache/backupRestore/backup
    private val tmpZipPath by lazy {
        backupRestorePath + File.separator + "backup"
    }

    private val tmpZipFile by lazy {
        backupRestorePath + File.separator + "backup.zip"
    }

    private fun createConfigFile(type: Type) {
        when (type) {
            is Type.Preference -> {
                val folder = internalDataFile.absolutePath + File.separator + "shared_prefs"
                FileUtils.copyFilesFromDir(
                    File(folder),
                    File(tmpZipPath + File.separator + "shared_prefs"),
                )
            }

            is Type.List -> {
                encodeJsonAndCopyToTmpZipPath(dbm.systemTtsV2.getAllGroupWithTts(), "list")
            }

            is Type.SpeechRule -> {
                encodeJsonAndCopyToTmpZipPath(dbm.speechRuleDao.all, "speechRules")
            }

            is Type.ReplaceRule -> {
                encodeJsonAndCopyToTmpZipPath(
                    dbm.replaceRuleDao.allGroupWithReplaceRules(),
                    "replaceRules"
                )
            }

            is Type.IPlugin -> {
                if (type.includeVars) {
                    encodeJsonAndCopyToTmpZipPath(dbm.pluginDao.all, "plugins")
                } else {
                    encodeJsonAndCopyToTmpZipPath(dbm.pluginDao.all.map {
                        it.userVars = mutableMapOf()
                        it
                    }, "plugins")
                }
            }
        }
    }

    private inline fun <reified T> encodeJsonAndCopyToTmpZipPath(v: T, name: String) {
        val s = AppConst.jsonBuilder.encodeToString(v)
        File(tmpZipPath + File.separator + name + ".json").writeText(s)
    }
}
