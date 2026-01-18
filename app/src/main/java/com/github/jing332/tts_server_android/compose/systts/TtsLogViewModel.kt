package com.github.jing332.tts_server_android.compose.systts

import android.util.Log 
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drake.net.utils.withMain
import com.github.jing332.common.LogEntry
import com.github.jing332.common.LogLevel
import com.github.jing332.common.toLogLevel
import com.github.jing332.common.utils.runOnUI
import com.github.jing332.tts_server_android.SysttsLogger
import com.github.jing332.tts_server_android.constant.AppConst
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter

class TtsLogViewModel : ViewModel() {
    companion object {
        const val TAG = "TtsLogViewModel"
        const val MAX_SIZE = 150
        
        // 修改点：路径从 files/log 指向 cache/log
        // AppConst.externalFilesDir 指向 .../files，parentFile 指向 .../包名，再 resolve cache 即为 cache 目录
        val file = File(AppConst.externalFilesDir.parentFile, "cache/log/system_tts.log")
    }

    val logs = mutableStateListOf<LogEntry>()

    fun clear() {
        logs.clear()
        runCatching {
            FileWriter(file, false).use { it.write(CharArray(0)) }
        }.onFailure {
            logs.add(LogEntry(level = LogLevel.ERROR, message = it.stackTraceToString()))
            Log.e(TAG, "clear: ", it) 
        }
    }


    private fun toLogEntry(line: String): LogEntry {
        return line.split(" | ").let {
            val time = it[0]
            val level = it[1]
            val msg = it[2]
            LogEntry(
                level = level.toLogLevel(), time = time, message = msg
            )
        }
    }

    fun logDir(): String {
        return file.absolutePath
    }

    init {
        try {
            viewModelScope.launch(Dispatchers.IO) {
                pull()
                SysttsLogger.register({ log ->
                    runOnUI {
                        logs.add(log)
                    }
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "init: ", e) 
        }
    }

    fun add(line: String) {
        try {
            val logEntry = toLogEntry(line)
            if (logs.size > MAX_SIZE)
                logs.removeRange(0, 10)
            logs.add(logEntry)

        } catch (e: Exception) {
            Log.e(TAG, "add: ", e) 
        }
    }

    @Suppress("DEPRECATION")
    suspend fun pull() {
        runCatching {
            if (file.exists()) {
                file.readLines().takeLast(MAX_SIZE).apply {
                    withMain {
                        forEach { add(it) }
                    }
                }
            }
        }.onFailure {
            logs.add(LogEntry(level = LogLevel.ERROR, message = it.stackTraceToString()))
            Log.e(TAG, "pull: ", it) 
        }

    }
}
