package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.SmPatch

@Composable
fun BossDefeatedEditor(
    patch: SmPatch,
    editorState: EditorState,
    modifier: Modifier = Modifier
) {
    @Suppress("UNUSED_VARIABLE") val pv = editorState.patchVersion
    val stored = patch.configData ?: mutableMapOf()

    Column(
        modifier = modifier.fillMaxSize().padding(20.dp)
    ) {
        Text("Boss Defeated Flags", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Mark bosses as already defeated. Rooms load in their post-boss state. " +
            "All four main bosses (Kraid, Phantoon, Ridley, Draygon) must be defeated to unlock Tourian.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Main Bosses (required for Tourian)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFFF5722))
                Spacer(Modifier.height(8.dp))

                for (flag in BOSS_FLAG_DEFS.filter { it.key in setOf("kraid", "phantoon", "ridley", "draygon") }) {
                    BossCheckRow(flag, stored, editorState, patch.id)
                }

                Spacer(Modifier.height(16.dp))
                Text("Mini-Bosses", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF4CAF50))
                Spacer(Modifier.height(8.dp))

                for (flag in BOSS_FLAG_DEFS.filter { it.key !in setOf("kraid", "phantoon", "ridley", "draygon") }) {
                    BossCheckRow(flag, stored, editorState, patch.id)
                }

                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            for (flag in BOSS_FLAG_DEFS) {
                                editorState.setPatchConfigData(patch.id, flag.key, 1)
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) { Text("Defeat All", fontSize = 12.sp) }

                    OutlinedButton(
                        onClick = {
                            for (flag in BOSS_FLAG_DEFS) {
                                editorState.setPatchConfigData(patch.id, flag.key, 0)
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) { Text("Clear All", fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun BossCheckRow(
    flag: BossFlagDef,
    stored: Map<String, Int>,
    editorState: EditorState,
    patchId: String
) {
    val checked = (stored[flag.key] ?: 0) != 0
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = {
                editorState.setPatchConfigData(patchId, flag.key, if (it) 1 else 0)
            },
            modifier = Modifier.size(20.dp),
            colors = CheckboxDefaults.colors(
                checkedColor = Color(0xFF4CAF50),
                uncheckedColor = Color(0xFF888888),
                checkmarkColor = Color.White
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            flag.name,
            fontSize = 13.sp,
            fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal,
            color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}
