package com.supermetroid.editor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.data.SmPatch
import com.supermetroid.editor.rom.RomParser

// ─── Samus Physics data definitions ─────────────────────────────────
// All values are in bank $91 (SNES addresses). Values are 16-bit LE.
// PC offset = ((0x91 & 0x7F) * 0x8000) + (snesOffset - 0x8000) = 0x088000 + offset

data class PhysicsField(
    val key: String,
    val label: String,
    val pcOffset: Int,      // PC file offset
    val defaultValue: Int,  // Vanilla ROM value
    val description: String = "",
)

data class PhysicsCategory(
    val name: String,
    val color: Color,
    val fields: List<PhysicsField>,
)

// ─── Physics value addresses ────────────────────────────────────────
// Addresses sourced from SM disassembly (bank $91) and SMILE SamusForm.frm
// All are 16-bit LE values at the given PC offsets.

val PHYSICS_CATEGORIES = listOf(
    PhysicsCategory("Jump", Color(0xFF2196F3), listOf(
        // Normal jump speed (initial upward velocity)
        PhysicsField("jump_speed", "Normal Jump Speed", 0x081EA0, 0x0500, "Initial Y velocity for a standing jump"),
        // High jump boots speed
        PhysicsField("hijump_speed", "Hi-Jump Speed", 0x081EA4, 0x0600, "Initial Y velocity with Hi-Jump Boots"),
        // Spin jump speed
        PhysicsField("spinjump_speed", "Spin Jump Speed", 0x081EA8, 0x04E0, "Initial Y velocity for spin jump"),
        // Spin jump hi-jump
        PhysicsField("spinjump_hijump", "Spin Jump + Hi-Jump", 0x081EAC, 0x05E0, "Spin jump with Hi-Jump Boots"),
        // Underwater jump
        PhysicsField("water_jump", "Underwater Jump Speed", 0x081EB0, 0x0280, "Jump speed underwater (no Gravity)"),
        // Wall jump speed
        PhysicsField("walljump_speed", "Walljump Speed", 0x081006, 0x00FF, "Horizontal push-off from wall"),
    )),
    PhysicsCategory("Gravity & Falling", Color(0xFF9C27B0), listOf(
        PhysicsField("gravity", "Gravity", 0x081EA2, 0x001C, "Downward acceleration per frame"),
        PhysicsField("max_fall", "Max Fall Speed", 0x081EB4, 0x0500, "Terminal velocity when falling"),
        PhysicsField("water_gravity", "Underwater Gravity", 0x081EB8, 0x000E, "Gravity underwater (no Gravity Suit)"),
        PhysicsField("water_max_fall", "Underwater Max Fall", 0x081EBC, 0x0280, "Max fall speed underwater"),
        PhysicsField("lava_gravity", "Lava/Acid Gravity", 0x081EC0, 0x0010, "Gravity in lava or acid"),
    )),
    PhysicsCategory("Running", Color(0xFF4CAF50), listOf(
        PhysicsField("run_accel", "Run Acceleration", 0x081B34, 0x000E, "Ground acceleration per frame"),
        PhysicsField("run_max", "Run Max Speed", 0x081B30, 0x0180, "Maximum running speed"),
        PhysicsField("run_decel", "Run Deceleration", 0x081B38, 0x0020, "Friction when releasing run"),
        PhysicsField("moonwalk_max", "Moonwalk Max Speed", 0x081B40, 0x0100, "Maximum backward moonwalk speed"),
    )),
    PhysicsCategory("Air Control", Color(0xFF00BCD4), listOf(
        PhysicsField("air_accel", "Air Horizontal Accel", 0x081B2C, 0x000A, "Horizontal acceleration in midair"),
        PhysicsField("air_max", "Air Max Horizontal", 0x081B28, 0x0100, "Maximum horizontal speed in air"),
        PhysicsField("air_friction", "Air Physics Mode", 0x081B2F, 0x0002, "02=normal, 04=realistic (no air control)"),
    )),
    PhysicsCategory("Speed Booster", Color(0xFFFF9800), listOf(
        PhysicsField("speedboost_accel", "Speed Booster Accel", 0x081B44, 0x0004, "Acceleration during speed boost"),
        PhysicsField("speedboost_max", "Speed Booster Max", 0x081B48, 0x0400, "Maximum speed boost velocity"),
        PhysicsField("shinespark_speed", "Shinespark Speed", 0x081B4C, 0x0800, "Shinespark dash velocity"),
    )),
    PhysicsCategory("Damage & Knockback", Color(0xFFF44336), listOf(
        PhysicsField("kb_speed_x", "Knockback X Speed", 0x081B50, 0x0300, "Horizontal knockback velocity"),
        PhysicsField("kb_speed_y", "Knockback Y Speed", 0x081B54, 0x0200, "Vertical knockback velocity"),
        PhysicsField("kb_duration", "Knockback Duration", 0x081B58, 0x000C, "Frames of knockback stun"),
        PhysicsField("iframes", "I-Frame Duration", 0x081B5C, 0x003C, "Invincibility frames after hit"),
    )),
)

val ALL_PHYSICS_FIELDS: List<PhysicsField> = PHYSICS_CATEGORIES.flatMap { it.fields }

// ─── ROM access ────────────────────────────────────────────────────

private fun readPhysicsValue(romParser: RomParser?, field: PhysicsField): Int? {
    if (romParser == null) return null
    return try {
        val rom = romParser.getRomData()
        val pc = field.pcOffset
        if (pc + 1 < rom.size) {
            (rom[pc].toInt() and 0xFF) or ((rom[pc + 1].toInt() and 0xFF) shl 8)
        } else null
    } catch (_: Exception) { null }
}

// ─── Main composable ───────────────────────────────────────────────

@Composable
fun SamusPhysicsEditor(
    patch: SmPatch,
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier
) {
    val values = remember(patch.id, editorState.patchVersion) {
        val map = mutableStateMapOf<String, Int>()
        val stored = patch.configData
        for (field in ALL_PHYSICS_FIELDS) {
            map[field.key] = stored?.get(field.key)
                ?: readPhysicsValue(romParser, field)
                ?: field.defaultValue
        }
        map
    }

    fun apply(field: PhysicsField, value: Int) {
        values[field.key] = value
        editorState.setPatchConfigData(patch.id, field.key, value)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("Samus Physics Editor", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Edit Samus movement physics — jump heights, gravity, run speed, air control, and more. " +
            "Values are 16-bit. Changes apply when patch is enabled.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        for (cat in PHYSICS_CATEGORIES) {
            PhysicsCategoryCard(cat, values, ::apply)
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                for (field in ALL_PHYSICS_FIELDS) {
                    apply(field, readPhysicsValue(romParser, field) ?: field.defaultValue)
                }
            },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Text("Reset All to ROM Defaults", fontSize = 12.sp)
        }
    }
}

@Composable
private fun PhysicsCategoryCard(
    category: PhysicsCategory,
    values: Map<String, Int>,
    onApply: (PhysicsField, Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = category.color.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, category.color.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(category.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = category.color)
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Property", fontSize = 10.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                Text("Value", fontSize = 10.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(80.dp), textAlign = TextAlign.Center)
                Text("Hex", fontSize = 10.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(60.dp), textAlign = TextAlign.Center)
            }
            Divider(modifier = Modifier.padding(vertical = 4.dp))

            for (field in category.fields) {
                PhysicsRow(field, values[field.key] ?: field.defaultValue, { onApply(field, it) })
            }
        }
    }
}

@Composable
private fun PhysicsRow(
    field: PhysicsField,
    value: Int,
    onChange: (Int) -> Unit
) {
    val isModified = value != field.defaultValue

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                field.label,
                fontSize = 12.sp,
                fontWeight = if (isModified) FontWeight.Medium else FontWeight.Normal,
                color = if (isModified) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
            )
            if (field.description.isNotEmpty()) {
                Text(
                    field.description,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        PhysicsInput(value, onChange, Modifier.width(80.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            String.format("%04X", value),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(60.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PhysicsInput(
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember(value) { mutableStateOf(value.toString()) }

    BasicTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter { it.isDigit() }.take(5)
            text = filtered
            filtered.toIntOrNull()?.let { onChange(it.coerceIn(0, 65535)) }
        },
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .height(28.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    )
}
