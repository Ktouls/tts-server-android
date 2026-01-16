package com.github.jing332.tts_server_android.conf

import android.content.Context
import com.funny.data_saver.core.DataSaverConverter.registerTypeConverters
import com.funny.data_saver.core.DataSaverPreferences
import com.funny.data_saver.core.mutableDataSaverStateOf
import com.github.jing332.tts_server_android.app
import com.github.jing332.tts_server_android.constant.CodeEditorTheme

object CodeEditorConfig {
    private val pref by lazy { DataSaverPreferences((app as Context).getSharedPreferences("code_editor", 0)) }

    init {
        registerTypeConverters(
            save = { it.id },
            restore = { value ->
                CodeEditorTheme.values().find { it.id == value } ?: CodeEditorTheme.AUTO
            }
        )
    }

    val theme by lazy { mutableDataSaverStateOf(pref, "codeEditorTheme", CodeEditorTheme.AUTO) }

    val isWordWrapEnabled by lazy { mutableDataSaverStateOf(pref, "isWordWrapEnabled", false) }
    val isRemoteSyncEnabled by lazy { mutableDataSaverStateOf(pref, "isRemoteSyncEnabled", false) }
    val remoteSyncPort by lazy { mutableDataSaverStateOf(pref, "remoteSyncPort", 4566) }
}
