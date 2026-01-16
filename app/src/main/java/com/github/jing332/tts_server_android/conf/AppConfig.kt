package com.github.jing332.tts_server_android.conf

import com.funny.data_saver.core.DataSaverConverter.registerTypeConverters
import com.funny.data_saver.core.DataSaverPreferences
import com.funny.data_saver.core.mutableDataSaverStateOf
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.app
import com.github.jing332.tts_server_android.compose.theme.AppTheme
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object AppConfig {
    @OptIn(ExperimentalSerializationApi::class)
    private val json by lazy {
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            allowStructuredMapKeys = true
        }
    }

    init {
        registerTypeConverters<List<Pair<String, String>>>(
            save = { json.encodeToString(it) },
            restore = {
                val list: List<Pair<String, String>> = try {
                    json.decodeFromString(it)
                } catch (_: Exception) {
                    emptyList()
                }
                list
            }
        )

        registerTypeConverters(
            save = { it.id },
            restore = { value ->
                AppTheme.values().find { it.id == value } ?: AppTheme.DEFAULT
            }
        )
    }

    /**
     * 关键修复 1：使用 by lazy 延迟初始化。
     * 只有在 App 真正运行起来并第一次访问配置项时，才会去调用 app 实例。
     */
    private val dataSaverPref by lazy { DataSaverPreferences(app.getSharedPreferences("app", 0)) }

    val theme = mutableDataSaverStateOf(
        dataSaverInterface = dataSaverPref,
        key = "theme",
        initialValue = AppTheme.DEFAULT
    )

    val limitTagLength = mutableDataSaverStateOf(
        dataSaverInterface = dataSaverPref,
        key = "limitTagLength",
        initialValue = 0
    )

    val limitNameLength = mutableDataSaverStateOf(
        dataSaverInterface = dataSaverPref,
        key = "limitNameLength",
        initialValue = 0
    )

    val isSwapListenAndEditButton = mutableDataSaverStateOf(
        dataSaverInterface = dataSaverPref,
        key = "isSwapListenAndEditButton",
        initialValue = false
    )

    val isAutoCheckUpdateEnabled = mutableDataSaverStateOf(
        dataSaverInterface = dataSaverPref,
        key = "isAutoCheckUpdateEnabled",
        initialValue = true
    )

    val isExcludeFromRecent = mutableDataSaverStateOf(
        dataSaverInterface = dataSaverPref,
        key = "isExcludeFromRecent",
        initialValue = false
    )

    val isEdgeDnsEnabled = mutableDataSaverStateOf(
        dataSaverInterface = dataSaverPref,
        key = "isEdgeDnsEnabled",
        initialValue = true
    )

    /**
     * 关键修复 2：initialValue 绝对不能引用 app.getString(...)。
     * 这里改用硬编码的字符串，确保类加载时不需要 Context。
     */
    val testSampleText = mutableDataSaverStateOf(
        dataSaverInterface = dataSaverPref,
        key = "testSampleText",
        initialValue = "单击右侧按钮即可测试并播放这段音频。如果一切正常，你应该能听到清晰的声音。"
    )

    val fragmentIndex = mutableDataSaverStateOf(
        dataSaverInterface = dataSaverPref,
        key = "fragmentIndex",
        initialValue = 0
    )

    val filePickerMode = mutableDataSaverStateOf(
        dataSaverInterface = dataSaverPref,
        key = "filePickerMode",
        initialValue = 0
    )

    val spinnerMaxDropDownCount = mutableDataSaverStateOf(
        dataSaverInterface = dataSaverPref,
        key = "spinnerMaxDropDownCount",
        initialValue = 20
    )

    val lastReadHelpDocumentVersion = mutableDataSaverStateOf(
        dataSaverInterface = dataSaverPref,
        key = "lastReadHelpDocumentVersion",
        initialValue = 0
    )

    // ================== WebDAV 配置 ==================
    val webDavUrl = mutableDataSaverStateOf(
        dataSaverInterface = dataSaverPref,
        key = "webDavUrl",
        initialValue = ""
    )

    val webDavUser = mutableDataSaverStateOf(
        dataSaverInterface = dataSaverPref,
        key = "webDavUser",
        initialValue = ""
    )

    val webDavPass = mutableDataSaverStateOf(
        dataSaverInterface = dataSaverPref,
        key = "webDavPass",
        initialValue = ""
    )

    val webDavPath = mutableDataSaverStateOf(
        dataSaverInterface = dataSaverPref,
        key = "webDavPath",
        initialValue = "/TTS备份"
    )
}
