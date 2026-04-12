package com.supermetroid.editor.emulator

import kotlinx.serialization.Serializable

@Serializable
data class LibretroWorkerRequest(
    val id: String,
    val command: String,
    val sessionConfig: SessionConfig? = null,
    val input: EmulatorInput? = null,
    val name: String? = null,
    val address: Int? = null,
    val size: Int? = null,
    val dataBase64: String? = null,
    val muted: Boolean? = null,
)

@Serializable
data class LibretroWorkerResponse(
    val id: String? = null,
    val ok: Boolean = true,
    val error: String? = null,
    val capabilities: EmulatorCapabilities? = null,
    val stepResult: StepResult? = null,
    val snapshot: GameSnapshot? = null,
    val states: List<StateInfo> = emptyList(),
    val dataBase64: String? = null,
)
