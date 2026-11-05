package com.android.gudao.iphoneos.ui

import java.util.Locale

object Fmt {
    fun bytes(v: Long): String = when {
        v <= 0 -> "0 B"
        v >= 1L shl 30 -> "%.2f GB".format(v / 1_073_741_824.0)
        v >= 1L shl 20 -> "%.0f MB".format(v / 1_048_576.0)
        v >= 1L shl 10 -> "%.0f KB".format(v / 1024.0)
        else -> "$v B"
    }

    fun speed(bytesPerSec: Long): String =
        if (bytesPerSec <= 0) "—" else "${bytes(bytesPerSec)}/s"

    fun duration(seconds: Long): String {
        if (seconds < 0) return "—"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "%d:%02d:%02d".format(h, m, s)
            else -> "%d:%02d".format(m, s)
        }
    }

    fun elapsed(sinceEpochMs: Long): String {
        if (sinceEpochMs <= 0) return "—"
        val s = (System.currentTimeMillis() - sinceEpochMs) / 1000
        return duration(s)
    }

    fun eta(seconds: Long?): String = when {
        seconds == null -> "计算中…"
        seconds <= 0 -> "即将完成"
        else -> "剩余 ${duration(seconds)}"
    }

    fun percent(fraction: Float): String = "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%"

    fun mb(bytes: Long): String = String.format(Locale.US, "%.0f MB", bytes / 1_048_576.0)
}
