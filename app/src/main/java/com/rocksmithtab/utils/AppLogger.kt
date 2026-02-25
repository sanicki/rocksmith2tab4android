package com.rocksmithtab.utils

import android.util.Log

object AppLogger {
    private val logBuffer = StringBuilder()

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        append("DEBUG", tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        append("WARN", tag, message)
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        Log.e(tag, message, t)
        append("ERROR", tag, "$message ${t?.message ?: ""}")
    }

    private fun append(level: String, tag: String, message: String) {
        synchronized(logBuffer) {
            logBuffer.append("[$level] $tag: $message\n")
        }
    }

    fun getLogText(): String {
        return synchronized(logBuffer) {
            logBuffer.toString()
        }
    }

    fun clear() {
        synchronized(logBuffer) {
            logBuffer.clear()
        }
    }
}
