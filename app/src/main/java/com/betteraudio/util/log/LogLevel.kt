package com.betteraudio.util.log

/**
 * File-logging enablement level — mirrors [com.betteraudio.data.settings.SettingsStore.logLevel]
 * ("OFF" | "ON" | "VERBOSE"). OFF still persists ERROR lines and a crash trail (see
 * [com.betteraudio.util.AppLog]'s crash handler); ON persists INFO and above; VERBOSE adds DEBUG.
 */
enum class LogLevel {
    OFF, ON, VERBOSE;

    companion object {
        fun from(raw: String): LogLevel = entries.find { it.name == raw } ?: OFF
    }
}

/** Per-line severity, matching Android's D/I/W/E convention. */
enum class Severity(val code: Char) {
    DEBUG('D'), INFO('I'), WARN('W'), ERROR('E')
}
