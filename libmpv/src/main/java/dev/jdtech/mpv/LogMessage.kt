package dev.jdtech.mpv

data class LogMessage(
    val prefix: String,
    val level: LogLevel,
    val text: String,
)
