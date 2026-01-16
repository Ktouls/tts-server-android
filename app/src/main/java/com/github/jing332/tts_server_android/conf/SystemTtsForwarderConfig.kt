package com.github.jing332.tts_server_android.conf

import com.funny.data_saver.core.DataSaverPreferences
import com.funny.data_saver.core.mutableDataSaverStateOf
import com.github.jing332.tts_server_android.app

object SystemTtsForwarderConfig {
    private val pref = DataSaverPreferences(app.getSharedPreferences("systts_forwarder", 0))

    val port = mutableDataSaverStateOf(
        dataSaverInterface = pref,
        key = "port",
        initialValue = 1221
    )

    val isWakeLockEnabled = mutableDataSaverStateOf(
        dataSaverInterface = pref,
        key = "isWakeLockEnabled",
        initialValue = false
    )

    // 👇👇👇 新增：是否自动启动 (记忆上次状态) 👇👇👇
    val isAutoStart = mutableDataSaverStateOf(
        dataSaverInterface = pref,
        key = "isAutoStart",
        initialValue = false
    )
}
