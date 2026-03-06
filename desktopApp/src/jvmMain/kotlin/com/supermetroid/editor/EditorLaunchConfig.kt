package com.supermetroid.editor

import java.io.File

data class EditorLaunchConfig(
    val openEmulatorWorkspace: Boolean = false,
    val romPath: String? = null,
    val navExportDir: String = "/tmp/sm_export",
    val bootStateName: String = "ZebesStart",
    val controlMode: String = "manual",
    val roomHandle: String = "landingSite",
    val autoStartSession: Boolean = false,
) {
    companion object {
        fun fromEnvironment(): EditorLaunchConfig {
            fun env(name: String): String? = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
            fun envBool(name: String): Boolean {
                return when (env(name)?.lowercase()) {
                    "1", "true", "yes", "on" -> true
                    else -> false
                }
            }

            val romPath = env("SMEDIT_ROM_PATH")
            val navExportDir = env("SMEDIT_NAV_EXPORT_DIR") ?: "/tmp/sm_export"
            val bootStateName = env("SMEDIT_BOOT_STATE") ?: "ZebesStart"
            val controlMode = env("SMEDIT_CONTROL_MODE") ?: "manual"
            val roomHandle = env("SMEDIT_ROOM_HANDLE") ?: "landingSite"
            val openEmulatorWorkspace = envBool("SMEDIT_OPEN_EMU") || envBool("SMEDIT_AUTO_START")
            val autoStartSession = envBool("SMEDIT_AUTO_START")

            return EditorLaunchConfig(
                openEmulatorWorkspace = openEmulatorWorkspace,
                romPath = romPath?.takeIf { File(it).exists() },
                navExportDir = navExportDir,
                bootStateName = bootStateName,
                controlMode = controlMode,
                roomHandle = roomHandle,
                autoStartSession = autoStartSession,
            )
        }
    }
}
