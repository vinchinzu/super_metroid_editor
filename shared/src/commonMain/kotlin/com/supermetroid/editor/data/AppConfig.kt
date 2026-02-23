package com.supermetroid.editor.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class WindowConfig(
    val x: Int = -1,
    val y: Int = -1,
    val width: Int = 1400,
    val height: Int = 900,
)

@Serializable
data class AppSettings(
    val lastRomPath: String? = null,
    val window: WindowConfig = WindowConfig(),
    val lastRoomPerRom: Map<String, String> = emptyMap(),
)

object AppConfig {
    private val configDir = File(System.getProperty("user.home"), ".smedit")
    private val configFile = File(configDir, "config.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private var cached: AppSettings? = null

    fun load(): AppSettings {
        cached?.let { return it }
        if (!configFile.exists()) {
            val defaults = AppSettings()
            cached = defaults
            return defaults
        }
        return try {
            val settings = json.decodeFromString<AppSettings>(configFile.readText())
            cached = settings
            settings
        } catch (e: Exception) {
            System.err.println("[Config] Failed to read config: ${e.message}")
            val defaults = AppSettings()
            cached = defaults
            defaults
        }
    }

    fun save(settings: AppSettings) {
        cached = settings
        try {
            configDir.mkdirs()
            configFile.writeText(json.encodeToString(settings))
        } catch (e: Exception) {
            System.err.println("[Config] Failed to write config: ${e.message}")
        }
    }

    fun update(block: AppSettings.() -> AppSettings) {
        save(block(load()))
    }
}
