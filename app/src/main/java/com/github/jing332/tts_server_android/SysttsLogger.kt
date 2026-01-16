package com.github.jing332.tts_server_android

import com.github.jing332.common.LogEntry
import java.util.concurrent.CopyOnWriteArraySet

object SysttsLogger {
    private val listeners = CopyOnWriteArraySet<LogListener>()
    fun log(entry: LogEntry) {
        listeners.forEach { it.log(entry) }
    }

    fun register(listener: LogListener) {
        listeners.add(listener)
    }

    fun unregister(listener: LogListener) {
        listeners.remove(listener)
    }

    fun interface LogListener {
        fun log(entry: LogEntry)
    }
}
