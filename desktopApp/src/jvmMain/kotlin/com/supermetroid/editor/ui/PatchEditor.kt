package com.supermetroid.editor.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.PatchRepository
import com.supermetroid.editor.data.PatchWrite
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.rom.RomParser
import java.awt.FileDialog
import java.io.File

// ─── Left column: patch list panel ──────────────────────────────────────

@Composable
fun PatchListPanel(
    editorState: EditorState,
    modifier: Modifier = Modifier
) {
    @Suppress("UNUSED_VARIABLE") val pv = editorState.patchVersion
    val patches = editorState.project.patches
    val selectedId = editorState.selectedPatchId

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Patches", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = {
                        val fd = FileDialog(null as java.awt.Frame?, "Import IPS Patch", FileDialog.LOAD)
                        fd.file = "*.ips"
                        fd.isVisible = true
                        val dir = fd.directory
                        val file = fd.file
                        if (dir != null && file != null) {
                            try {
                                val ipsFile = File(dir, file)
                                val writes = PatchRepository.parseIps(ipsFile.readBytes())
                                val name = ipsFile.nameWithoutExtension.replace('_', ' ')
                                    .replaceFirstChar { it.uppercase() }
                                val patch = editorState.addPatch(name, "Imported from ${ipsFile.name}")
                                editorState.setPatchWrites(patch.id,
                                    writes.map { SmPatchWrite(it.offset, it.bytes) })
                            } catch (e: Exception) {
                                println("IPS import failed: ${e.message}")
                            }
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Text("IPS", fontSize = 11.sp)
                }
                Button(
                    onClick = { editorState.addPatch("New Patch") },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("+ Add", fontSize = 11.sp)
                }
            }
        }

        Divider()

        // Patch list
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            if (patches.isEmpty()) {
                Text(
                    "No patches yet.\nClick + Add to create one.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
            for (patch in patches) {
                PatchListItem(
                    patch = patch,
                    isSelected = patch.id == selectedId,
                    onSelect = { editorState.selectPatch(patch.id) },
                    onToggle = { editorState.togglePatch(patch.id) },
                    onDelete = { editorState.removePatch(patch.id) }
                )
            }
        }
    }
}

@Composable
private fun PatchListItem(
    patch: SmPatch,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primaryContainer
             else Color.Transparent

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(horizontal = 2.dp),
        color = bg,
        shape = RoundedCornerShape(3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Checkbox(
                checked = patch.enabled,
                onCheckedChange = { onToggle() },
                modifier = Modifier.size(16.dp),
                colors = CheckboxDefaults.colors(
                    checkedColor = Color(0xFF4CAF50),
                    uncheckedColor = Color(0xFF888888),
                    checkmarkColor = Color.White
                )
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    patch.name,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (patch.enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            Text(
                "✕",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                modifier = Modifier
                    .clickable { onDelete() }
                    .padding(2.dp)
            )
        }
    }
}

// ─── Right canvas: patch editor view ────────────────────────────────────

@Composable
fun PatchEditorCanvas(
    editorState: EditorState,
    romParser: RomParser? = null,
    modifier: Modifier = Modifier
) {
    @Suppress("UNUSED_VARIABLE") val pv = editorState.patchVersion
    val patch = editorState.getSelectedPatch()

    if (patch == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Select a patch from the left panel", fontSize = 14.sp,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("or click + Add to create a new one.", fontSize = 12.sp,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Toolbar
        PatchToolbar(patch, editorState)
        Divider()
        // Config panel for GUI patches, hex editor for manual patches
        if (patch.configType == "ceres_escape_seconds") {
            CeresEscapeTimeConfig(patch, editorState, romParser, Modifier.weight(1f).fillMaxWidth())
        } else {
            PatchHexEditor(patch, editorState, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun PatchToolbar(patch: SmPatch, editorState: EditorState) {
    var editingName by remember(patch.id) { mutableStateOf(false) }
    var nameText by remember(patch.id) { mutableStateOf(patch.name) }
    var descText by remember(patch.id) { mutableStateOf(patch.description) }

    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Editable name
            if (editingName) {
                BasicTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Button(
                    onClick = {
                        editorState.updatePatch(patch.id, name = nameText, description = descText)
                        editingName = false
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) { Text("Save", fontSize = 11.sp) }
            } else {
                Text(
                    patch.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).clickable { editingName = true }
                )
                // Enabled badge
                Surface(
                    color = if (patch.enabled) Color(0xFF4CAF50) else Color(0xFF888888),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        if (patch.enabled) "ENABLED" else "DISABLED",
                        fontSize = 10.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Button(
                    onClick = { editorState.togglePatch(patch.id) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (patch.enabled) Color(0xFF888888) else Color(0xFF4CAF50)
                    )
                ) {
                    Text(if (patch.enabled) "Disable" else "Enable", fontSize = 11.sp)
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Description
        if (editingName) {
            BasicTextField(
                value = descText,
                onValueChange = { descText = it },
                textStyle = TextStyle(
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                    .padding(8.dp)
            )
        } else if (patch.description.isNotEmpty()) {
            Text(
                patch.description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { editingName = true }
            )
        }
    }
}

// ─── Config patch: Ceres escape time ────────────────────────────────────

private val CERES_ESCAPE_OPTIONS = listOf(15, 16, 20, 30, 45, 60, 90, 120, 150, 180, 240, 300, 360, 420, 480, 540, 600)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CeresEscapeTimeConfig(
    patch: SmPatch,
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier
) {
    val currentValue = (patch.configValue ?: 60).coerceIn(15, 600)
    val romValue = remember(romParser) {
        romParser?.let {
            val off = it.snesToPc(CERES_TIMER_OPERAND_SNES)
            val rom = it.getRomData()
            if (off + 1 < rom.size) {
                val secsBcd = rom[off].toInt() and 0xFF
                val minsBcd = rom[off + 1].toInt() and 0xFF
                val secs = (secsBcd shr 4) * 10 + (secsBcd and 0x0F)
                val mins = (minsBcd shr 4) * 10 + (minsBcd and 0x0F)
                mins * 60 + secs
            } else 60
        } ?: 60
    }

    Column(modifier = modifier.padding(16.dp)) {
        Text(
            "Set Ceres station escape timer (seconds). Override applies when patch is enabled.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "ROM default: ${romValue}s",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (secs in CERES_ESCAPE_OPTIONS) {
                FilterChip(
                    selected = currentValue == secs,
                    onClick = { editorState.setPatchConfigValue(patch.id, secs) },
                    label = { Text("${secs}s") }
                )
            }
        }
    }
}

@Composable
private fun PatchHexEditor(patch: SmPatch, editorState: EditorState, modifier: Modifier) {
    var rawText by remember(patch.id, editorState.patchVersion) {
        mutableStateOf(writesToText(patch.writes))
    }
    var parseError by remember(patch.id) { mutableStateOf<String?>(null) }

    Column(modifier = modifier.padding(12.dp)) {
        // Instructions
        Text(
            "Format: one record per line — OFFSET: BB BB BB ...  (hex values, offset is PC file address)",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Example: 8F625: 22    or    15962: 04    or    8267F: EA EA EA",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = {
                    val result = parseText(rawText)
                    if (result.error != null) {
                        parseError = result.error
                    } else {
                        parseError = null
                        editorState.setPatchWrites(patch.id, result.writes)
                    }
                },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) { Text("Apply Changes", fontSize = 12.sp) }

            Button(
                onClick = { rawText = writesToText(patch.writes) },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) { Text("Revert", fontSize = 12.sp) }

            if (parseError != null) {
                Text(
                    parseError!!,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // The hex text editor
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1E1E2E), RoundedCornerShape(6.dp))
                .padding(2.dp)
        ) {
            val scrollState = rememberScrollState()
            BasicTextField(
                value = rawText,
                onValueChange = { rawText = it; parseError = null },
                textStyle = TextStyle(
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFCDD6F4),
                    lineHeight = 20.sp
                ),
                cursorBrush = SolidColor(Color(0xFFF5C2E7)),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(12.dp)
            )
        }
    }
}

// ─── Text ↔ PatchWrite conversion ───────────────────────────────────────

private fun writesToText(writes: List<PatchWrite>): String {
    if (writes.isEmpty()) return ""
    return writes.joinToString("\n") { w ->
        val addr = w.offset.toString(16).uppercase().padStart(5, '0')
        val bytes = w.bytes.joinToString(" ") {
            it.toString(16).uppercase().padStart(2, '0')
        }
        "$addr: $bytes"
    }
}

private data class ParseResult(
    val writes: List<SmPatchWrite> = emptyList(),
    val error: String? = null
)

private fun parseText(text: String): ParseResult {
    val writes = mutableListOf<SmPatchWrite>()
    for ((lineNum, raw) in text.lines().withIndex()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue

        val colonIdx = line.indexOf(':')
        if (colonIdx < 0) return ParseResult(error = "Line ${lineNum + 1}: missing ':' separator")

        val addrStr = line.substring(0, colonIdx).trim()
        val bytesStr = line.substring(colonIdx + 1).trim()

        val offset = try {
            addrStr.toLong(16)
        } catch (_: NumberFormatException) {
            return ParseResult(error = "Line ${lineNum + 1}: invalid address '$addrStr'")
        }

        if (bytesStr.isEmpty()) continue

        val byteTokens = bytesStr.split("\\s+".toRegex())
        val bytes = mutableListOf<Int>()
        for (token in byteTokens) {
            if (token.isEmpty()) continue
            val b = try {
                token.toInt(16)
            } catch (_: NumberFormatException) {
                return ParseResult(error = "Line ${lineNum + 1}: invalid byte '$token'")
            }
            if (b !in 0..255) return ParseResult(error = "Line ${lineNum + 1}: byte $token out of range (00-FF)")
            bytes.add(b)
        }
        if (bytes.isNotEmpty()) {
            writes.add(SmPatchWrite(offset, bytes))
        }
    }
    return ParseResult(writes = writes)
}
