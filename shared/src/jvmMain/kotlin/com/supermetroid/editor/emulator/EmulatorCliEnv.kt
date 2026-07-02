package com.supermetroid.editor.emulator

internal fun env(name: String): String? =
    System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }

internal fun String.parseIntFlexible(): Int =
    if (startsWith("0x", ignoreCase = true)) {
        substring(2).toInt(16)
    } else {
        toInt()
    }
