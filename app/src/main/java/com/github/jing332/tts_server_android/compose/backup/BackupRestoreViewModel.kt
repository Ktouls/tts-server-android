package com.github.jing332.tts_server_android.compose.backup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
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
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import com.thegrizzlylabs.sardineandroid.model.DavResource
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
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


    suspend fun restore(bytes: ByteArray): Boolean {
        var isRestart = false
        val outFileDir = File(restorePath)
        outFileDir.deleteRecursively() // 确保清理旧数据
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
                val target = File(tmpZipPath + File.separator + "shared_prefs")
                target.mkdirs()
                FileUtils.copyFilesFromDir(
                    File(folder),
                    target,
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

    // 👇👇👇 新增：WebDAV 和网络下载逻辑 👇👇👇

    private fun getSardine(): Sardine {
        val sardine = OkHttpSardine()
        sardine.setCredentials(AppConfig.webDavUser.value, AppConfig.webDavPass.value)
        return sardine
    }

    suspend fun testWebDav() = withIO {
        val sardine = getSardine()
        if (!sardine.exists(AppConfig.webDavUrl.value)) {
            throw Exception("Connection failed or path does not exist")
        }
    }

    suspend fun getWebDavBackupFiles(): List<DavResource> = withIO {
        val sardine = getSardine()
        val url = AppConfig.webDavUrl.value + AppConfig.webDavPath.value
        if (!sardine.exists(url)) {
            sardine.createDirectory(url)
            return@withIO emptyList()
        }
        sardine.list(url).filter { !it.isDirectory && it.name.endsWith(".zip") }
    }

    suspend fun downloadFromWebDav(fileName: String): ByteArray = withIO {
        val sardine = getSardine()
        val url = AppConfig.webDavUrl.value + AppConfig.webDavPath.value + "/" + fileName
        val stream = sardine.get(url)
        stream.readBytes()
    }

    suspend fun downloadFromUrl(url: String): ByteArray = withIO {
        val client = OkHttpClient()
        val req = Request.Builder().url(url).build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) throw Exception("Download failed: code=${resp.code}")
        resp.body?.bytes() ?: throw Exception("Body is empty")
    }
}
