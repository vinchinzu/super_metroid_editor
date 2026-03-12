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
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomParser

// ─── Common enemy definitions ────────────────────────────────────
// Species ID (bank $A0 pointer), HP at offset 4, damage at offset 6.

data class EnemyDef(
    val key: String,
    val name: String,
    val speciesId: Int,
    val defaultHp: Int,
    val defaultDamage: Int,
    val category: String = "Common"
)

// Defaults are fallbacks; actual values are always read from ROM at runtime.
val ENEMY_DEFS = listOf(
    // ── Crawlers & Hoppers ──
    EnemyDef("zoomer", "Zoomer", 0xDCFF, 15, 5, "Crawler"),
    EnemyDef("zoomer_grey", "Zoomer (grey)", 0xD75F, 15, 5, "Crawler"),
    EnemyDef("geemer", "Geemer", 0xD91F, 50, 10, "Crawler"),
    EnemyDef("geemer_horiz", "Geemer (horizontal)", 0xDC3F, 50, 10, "Crawler"),
    EnemyDef("sidehopper", "Sidehopper", 0xD93F, 60, 20, "Hopper"),
    EnemyDef("sidehopper_large", "Sidehopper (large)", 0xD97F, 200, 30, "Hopper"),
    EnemyDef("sidehopper_big", "Sidehopper (big)", 0xD99F, 400, 60, "Hopper"),
    EnemyDef("dessgeega", "Dessgeega", 0xD9BF, 400, 40, "Hopper"),
    EnemyDef("dessgeega_big", "Dessgeega (big)", 0xD9DF, 800, 80, "Hopper"),

    // ── Flyers ──
    EnemyDef("skree", "Skree", 0xD7FF, 40, 16, "Flyer"),
    EnemyDef("reo", "Reo", 0xD87F, 20, 40, "Flyer"),
    EnemyDef("waver", "Waver", 0xD63F, 100, 16, "Flyer"),
    EnemyDef("ripper", "Ripper", 0xD47F, 200, 5, "Flyer"),
    EnemyDef("ripper2", "Ripper II", 0xD3FF, 400, 20, "Flyer"),
    EnemyDef("kihunter", "Kihunter", 0xDFBF, 20, 40, "Flyer"),
    EnemyDef("kihunter_green", "Kihunter (green)", 0xE03F, 400, 30, "Flyer"),

    // ── Wall & Ceiling ──
    EnemyDef("sciser", "Sciser", 0xD77F, 100, 12, "Crawler"),
    EnemyDef("zeela", "Zeela", 0xDC7F, 100, 16, "Crawler"),
    EnemyDef("sova", "Sova", 0xDD3F, 100, 16, "Crawler"),
    EnemyDef("beetom", "Beetom", 0xDCBF, 50, 8, "Crawler"),

    // ── Spawners & Pipes ──
    EnemyDef("rinka", "Rinka", 0xD23F, 10, 40, "Spawner"),
    EnemyDef("zeb", "Zeb", 0xF193, 20, 8, "Spawner"),
    EnemyDef("zebbo", "Zebbo", 0xF1D3, 20, 8, "Spawner"),
    EnemyDef("gamet", "Gamet", 0xF213, 20, 12, "Spawner"),

    // ── Aquatic (Maridia) ──
    EnemyDef("oum", "Oum", 0xD7BF, 200, 20, "Aquatic"),
    EnemyDef("skultera", "Skultera", 0xD6FF, 100, 12, "Aquatic"),
    EnemyDef("yard", "Yard", 0xDBBF, 100, 16, "Aquatic"),

    // ── Space Pirates ──
    EnemyDef("pirate_basic", "Space Pirate", 0xF353, 20, 15, "Pirate"),
    EnemyDef("pirate_norfair", "Space Pirate (Norfair)", 0xF413, 600, 40, "Pirate"),
    EnemyDef("pirate_maridia", "Space Pirate (Maridia)", 0xF453, 600, 40, "Pirate"),
    EnemyDef("pirate_tourian", "Space Pirate (Tourian)", 0xF493, 800, 50, "Pirate"),
    EnemyDef("pirate_mk2_norfair", "Space Pirate Mk.II (Norfair)", 0xF593, 1000, 60, "Pirate"),
    EnemyDef("pirate_mk2_tourian", "Space Pirate Mk.II (Tourian)", 0xF613, 1200, 70, "Pirate"),

    // ── Special ──
    EnemyDef("metroid", "Big Metroid", 0xEEBF, 1, 0, "Special"),
    EnemyDef("fireflea", "Fireflea", 0xD6BF, 1, 0, "Special"),
    EnemyDef("cacatac", "Cacatac", 0xCFFF, 200, 20, "Special"),
    EnemyDef("magdollite", "Magdollite", 0xD4BF, 200, 30, "Special"),
    EnemyDef("boyon", "Boyon", 0xCEBF, 100, 16, "Special"),
)

private val CATEGORY_ORDER = listOf("Crawler", "Hopper", "Flyer", "Spawner", "Aquatic", "Pirate", "Special")

// ─── Main composable ────────────────────────────────────────────

@Composable
fun EnemyStatsEditor(
    patch: SmPatch,
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier
) {
    val values = remember(patch.id, editorState.patchVersion) {
        val map = mutableStateMapOf<String, Int>()
        val stored = patch.configData
        for (e in ENEMY_DEFS) {
            map["${e.key}_hp"] = stored?.get("${e.key}_hp") ?: readEnemyStat(romParser, e.speciesId, 4) ?: e.defaultHp
            map["${e.key}_dmg"] = stored?.get("${e.key}_dmg") ?: readEnemyStat(romParser, e.speciesId, 6) ?: e.defaultDamage
        }
        map
    }

    fun apply(key: String, value: Int) {
        values[key] = value
        editorState.setPatchConfigData(patch.id, key, value)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("Enemy Stats Editor", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Edit HP and contact damage for common enemies. Changes apply when patch is enabled.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        val grouped = ENEMY_DEFS.groupBy { it.category }
        for (cat in CATEGORY_ORDER) {
            val enemies = grouped[cat] ?: continue
            EnemyCategorySection(cat, enemies, values, ::apply, romParser)
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                for (e in ENEMY_DEFS) {
                    apply("${e.key}_hp", readEnemyStat(romParser, e.speciesId, 4) ?: e.defaultHp)
                    apply("${e.key}_dmg", readEnemyStat(romParser, e.speciesId, 6) ?: e.defaultDamage)
                }
            },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Text("Reset All to ROM Defaults", fontSize = 12.sp)
        }
    }
}

@Composable
private fun EnemyCategorySection(
    category: String,
    enemies: List<EnemyDef>,
    values: Map<String, Int>,
    onApply: (String, Int) -> Unit,
    romParser: RomParser?
) {
    val catColor = when (category) {
        "Crawler" -> Color(0xFF8BC34A)
        "Hopper" -> Color(0xFF4CAF50)
        "Flyer" -> Color(0xFF2196F3)
        "Spawner" -> Color(0xFFFF9800)
        "Aquatic" -> Color(0xFF00BCD4)
        "Pirate" -> Color(0xFFF44336)
        "Special" -> Color(0xFF9C27B0)
        else -> Color.Gray
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = catColor.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, catColor.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(category, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = catColor)
            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Enemy", fontSize = 10.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                Text("HP", fontSize = 10.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(72.dp), textAlign = TextAlign.Center)
                Text("Damage", fontSize = 10.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(72.dp), textAlign = TextAlign.Center)
            }
            Divider(modifier = Modifier.padding(vertical = 4.dp))

            for (e in enemies) {
                EnemyRow(e, values, onApply)
            }
        }
    }
}

@Composable
private fun EnemyRow(
    enemy: EnemyDef,
    values: Map<String, Int>,
    onApply: (String, Int) -> Unit
) {
    val hp = values["${enemy.key}_hp"] ?: enemy.defaultHp
    val dmg = values["${enemy.key}_dmg"] ?: enemy.defaultDamage
    val hpModified = hp != enemy.defaultHp
    val dmgModified = dmg != enemy.defaultDamage

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            enemy.name,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
            fontWeight = if (hpModified || dmgModified) FontWeight.Medium else FontWeight.Normal,
            color = if (hpModified || dmgModified) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
        )
        EnemyStatInput(hp, { onApply("${enemy.key}_hp", it) }, Modifier.width(72.dp))
        Spacer(Modifier.width(4.dp))
        EnemyStatInput(dmg, { onApply("${enemy.key}_dmg", it) }, Modifier.width(72.dp))
    }
}

@Composable
private fun EnemyStatInput(
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
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .height(26.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 3.dp)
    )
}

// ─── ROM access ─────────────────────────────────────────────────

private fun readEnemyStat(romParser: RomParser?, speciesId: Int, offset: Int): Int? {
    if (romParser == null) return null
    return try {
        val snesAddr = RomConstants.BANK_ENEMY_AI or speciesId
        val pc = romParser.snesToPc(snesAddr) + offset
        val rom = romParser.getRomData()
        if (pc + 1 < rom.size) {
            (rom[pc].toInt() and 0xFF) or ((rom[pc + 1].toInt() and 0xFF) shl 8)
        } else null
    } catch (_: Exception) { null }
}
