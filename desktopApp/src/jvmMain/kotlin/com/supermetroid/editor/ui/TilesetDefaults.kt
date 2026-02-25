package com.supermetroid.editor.ui

import com.supermetroid.editor.data.PatchWrite
import com.supermetroid.editor.data.SmPatch

// ─── Tileset defaults: metatile → (block type, BTS) ─────────────

data class TileDefault(val blockType: Int, val bts: Int = 0)

/**
 * Hardcoded defaults for well-known metatiles. When a tile from the tileset
 * is placed on the map, these defaults are applied automatically so the user
 * doesn't have to right-click and set properties for every block.
 *
 * Block types: 0x0=Air, 0x3=Speed Booster, 0x4=Shot, 0x5=H-Extend, 0x8=Solid,
 *   0x9=Door, 0xA=Spike, 0xB=Crumble, 0xC=Shot(reform), 0xD=V-Extend,
 *   0xE=Grapple, 0xF=Bomb(reform)
 *
 * Type 0xC BTS: 0x00=beam/bomb(reform), 0x01=beam/bomb(no reform),
 *   0x04-0x07=hidden, 0x08-0x09=power bomb, 0x0A-0x0B=super missile
 * Type 0x3: speed booster breakable (solid, immune to shots/bombs, breaks on speed boost)
 */
object TilesetDefaults {
    val defaults: Map<Int, TileDefault> = mapOf(
        74  to TileDefault(0x4),
        75  to TileDefault(0x4),
        76  to TileDefault(0x4),
        77  to TileDefault(0x4),
        78  to TileDefault(0x4),
        79  to TileDefault(0x4),
        80  to TileDefault(0x4),
        81  to TileDefault(0x4),
        82  to TileDefault(0xC, 0x00),
        83  to TileDefault(0xC, 0x01),
        84  to TileDefault(0xC, 0x04),
        85  to TileDefault(0xC, 0x05),
        86  to TileDefault(0xA),
        87  to TileDefault(0xC, 0x08),
        88  to TileDefault(0xF),
        89  to TileDefault(0xF, 0x04),
        90  to TileDefault(0xA),
        91  to TileDefault(0xA),
        92  to TileDefault(0xA),
        114 to TileDefault(0x4, 0x00),
        155 to TileDefault(0xE),
        156 to TileDefault(0xA),
        157 to TileDefault(0xE, 0x01),
        158 to TileDefault(0xE, 0x02),
        159 to TileDefault(0xC, 0x0A),
        160 to TileDefault(0xC, 0x0B),
        182 to TileDefault(0x3, 0x08),
        183 to TileDefault(0xE),
        188 to TileDefault(0xB, 0x00),
        189 to TileDefault(0xB, 0x04),
        190 to TileDefault(0xB, 0x0E),
        191 to TileDefault(0xB, 0x0F),
        150 to TileDefault(0xC, 0x00),
        151 to TileDefault(0xC, 0x00),
        152 to TileDefault(0xC, 0x00),
        184 to TileDefault(0xC, 0x00),
        153 to TileDefault(0xC, 0x00),
        154 to TileDefault(0xC, 0x00),
        185 to TileDefault(0xC, 0x00),
        186 to TileDefault(0xC, 0x00),
        192 to TileDefault(0x2),
    )

    fun get(metatileIndex: Int): TileDefault? = defaults[metatileIndex]
}

// ─── Patch helpers ──────────────────────────────────────────────

/** UI-facing write entry (same shape as PatchWrite but not serializable). */
data class SmPatchWrite(val offset: Long, val bytes: List<Int>)

/**
 * Curated hex-edit patches from community documentation (begrimed.com hex edits).
 * All other patches load from bundled IPS in resources/patches/.
 */
val HARDCODED_PATCHES: List<SmPatch> = listOf(
    SmPatch(id = "hex_faster_charged_shots", name = "Faster Charged Shots",
        description = "Reduces delay between charged shots (0x3C → 0x1C at 0x83860).",
        enabled = false, writes = mutableListOf(PatchWrite(0x83860, listOf(0x1C)))),
    SmPatch(id = "hex_smooth_beam_shots", name = "Smooth Beam Shots",
        description = "Removes the flicker from uncharged beam shots for smoother appearance (0x9826B: D0 → 80).",
        enabled = false, writes = mutableListOf(PatchWrite(0x9826B, listOf(0x80)))),
    SmPatch(id = "hex_no_screen_shake", name = "No Screen Shake",
        description = "Disables all screen shaking effects globally (0x10BAF: NOP×4).",
        enabled = false, writes = mutableListOf(PatchWrite(0x10BAF, listOf(0xEA, 0xEA, 0xEA, 0xEA)))),
    SmPatch(id = "hex_infinite_missiles", name = "Infinite Missiles",
        description = "Missiles never deplete (0x83EBF: CE → AD).",
        enabled = false, writes = mutableListOf(PatchWrite(0x83EBF, listOf(0xAD)))),
    SmPatch(id = "hex_infinite_supers", name = "Infinite Super Missiles",
        description = "Super missiles never deplete (0x83EC4: CE → AD).",
        enabled = false, writes = mutableListOf(PatchWrite(0x83EC4, listOf(0xAD)))),
    SmPatch(id = "hex_infinite_pbs", name = "Infinite Power Bombs",
        description = "Power bombs never deplete (0x8402E: 8D → AD).",
        enabled = false, writes = mutableListOf(PatchWrite(0x8402E, listOf(0xAD)))),
    SmPatch(id = "hex_lower_gravity", name = "Lower Gravity",
        description = "Reduces planet gravity for floatier jumps (0x81EA2: 1C → 0C).",
        enabled = false, writes = mutableListOf(PatchWrite(0x81EA2, listOf(0x0C)))),
    SmPatch(id = "hex_higher_gravity", name = "Higher Gravity",
        description = "Increases planet gravity for heavier feel (0x81EA2: 1C → 2C).",
        enabled = false, writes = mutableListOf(PatchWrite(0x81EA2, listOf(0x2C)))),
    SmPatch(id = "hex_morph_ball_no_item", name = "Morph Ball Without Item",
        description = "Allows Morph Ball without collecting it (0x8F7D5: 16 → 00).",
        enabled = false, writes = mutableListOf(PatchWrite(0x8F7D5, listOf(0x00)))),
    SmPatch(id = "hex_space_jump_no_item", name = "Space Jump Without Item",
        description = "Enables space jump without having the item (0x82474: D0 → 80).",
        enabled = false, writes = mutableListOf(PatchWrite(0x82474, listOf(0x80)))),
    SmPatch(id = "hex_speed_boost_no_item", name = "Speed Booster Without Item",
        description = "Samus can run fast without collecting speed booster; no blue echoes until item equipped (0x8178C: 89 → A9).",
        enabled = false, writes = mutableListOf(PatchWrite(0x8178C, listOf(0xA9)))),
    SmPatch(id = "hex_no_spin_speed_loss", name = "No Spin Jump Speed Loss",
        description = "Samus doesn't lose speed turning left/right during spin jump (0x8F625: 23 → 22).",
        enabled = false, writes = mutableListOf(PatchWrite(0x8F625, listOf(0x22)))),
    SmPatch(id = "hex_instant_stop", name = "Instant Stop (No Skid)",
        description = "Disables the skid animation when turning; allows instant stopping (0x8267F: NOP×3).",
        enabled = false, writes = mutableListOf(PatchWrite(0x8267F, listOf(0xEA, 0xEA, 0xEA)))),
    SmPatch(id = "hex_disable_pseudo_screw", name = "Disable Pseudo Screw Attack",
        description = "Spinning jump during beam charge no longer does screw attack damage (0x824F5: NOP×3).",
        enabled = false, writes = mutableListOf(PatchWrite(0x824F5, listOf(0xEA, 0xEA, 0xEA)))),
    SmPatch(id = "hex_keep_blue_speed_air", name = "Keep Blue Speed in Air",
        description = "Moving left/right during spin jump no longer cancels speed booster blue effect (0x8F66F: NOP×4).",
        enabled = false, writes = mutableListOf(PatchWrite(0x8F66F, listOf(0xEA, 0xEA, 0xEA, 0xEA)))),
    SmPatch(id = "hex_always_charged_shots", name = "Always Fire Charged Shots",
        description = "Samus always fires charged shots (0x838D4: 00 → 10).",
        enabled = false, writes = mutableListOf(PatchWrite(0x838D4, listOf(0x10)))),
    SmPatch(id = "hex_disable_bomb_jump", name = "Disable Bomb Jump",
        description = "Bomb jumping no longer works (0x10B61: NOP×4).",
        enabled = false, writes = mutableListOf(PatchWrite(0x10B61, listOf(0xEA, 0xEA, 0xEA, 0xEA)))),
    SmPatch(id = "hex_speed_morph", name = "Speed Booster in Morph Ball",
        description = "Enables speed booster while in morph ball by holding run with spring ball (0x8054E: FF → 0F, 0x81775: FF → 0F).",
        enabled = false, writes = mutableListOf(PatchWrite(0x8054E, listOf(0x0F)), PatchWrite(0x81775, listOf(0x0F)))),
    SmPatch(id = "hex_gravity_no_heat_protect", name = "Gravity Suit No Heat Protection",
        description = "Removes Gravity suit's protection against heated rooms, making Varia always useful (0x6E37D: 21 → 01).",
        enabled = false, writes = mutableListOf(PatchWrite(0x6E37D, listOf(0x01)))),
    SmPatch(id = "hex_no_walljump_kickoff", name = "No Walljump Wall Push",
        description = "Walljumping no longer forces Samus away from the wall (0x81006: FF 00 → 00 00).",
        enabled = false, writes = mutableListOf(PatchWrite(0x81006, listOf(0x00, 0x00)))),
    SmPatch(id = "hex_supers_dont_open_reds", name = "Supers Don't Open Red Doors",
        description = "Super missiles no longer open red/missile doors or eye doors (0x23D58: NOP×5).",
        enabled = false, writes = mutableListOf(PatchWrite(0x23D58, listOf(0xEA, 0xEA, 0xEA, 0xEA, 0xEA)))),
    SmPatch(id = "hex_space_jump_underwater", name = "Space Jump in Water (No Gravity)",
        description = "Space jump works underwater/lava/acid without Gravity suit (0x82445: NOP×3).",
        enabled = false, writes = mutableListOf(PatchWrite(0x82445, listOf(0xEA, 0xEA, 0xEA)))),
    SmPatch(id = "hex_remove_all_trails", name = "Remove All Beam/Missile Trails",
        description = "Removes ALL trails from shots, charged shots, missiles, and SBAs (0x982F7: NOP×4).",
        enabled = false, writes = mutableListOf(PatchWrite(0x982F7, listOf(0xEA, 0xEA, 0xEA, 0xEA)))),
    SmPatch(id = "hex_fast_xray", name = "Fast X-Ray Scope",
        description = "X-ray scope beam widens almost instantly (0x4079A: 0A → 01).",
        enabled = false, writes = mutableListOf(PatchWrite(0x4079A, listOf(0x01)))),
    SmPatch(id = "hex_no_crystal_flash", name = "Disable Crystal Flash",
        description = "Crystal flash can no longer be used (0x40B5F: NOP×4).",
        enabled = false, writes = mutableListOf(PatchWrite(0x40B5F, listOf(0xEA, 0xEA, 0xEA, 0xEA)))),
    SmPatch(id = "hex_realistic_air_physics", name = "Realistic Air Physics",
        description = "No mid-air movement from standstill jumps or direction reversal in mid-air (0x81B2F: 02 → 04).",
        enabled = false, writes = mutableListOf(PatchWrite(0x81B2F, listOf(0x04)))),
    SmPatch(id = "hex_fast_shinespark_recovery", name = "Fast Shinespark Recovery",
        description = "Greatly reduces wait time after shinesparking into a wall (0x85396: AD C0 → 80 27).",
        enabled = false, writes = mutableListOf(PatchWrite(0x85396, listOf(0x80, 0x27)))),
    SmPatch(id = "hex_disable_grapple_camera_scroll", name = "Disable Grapple Slow Camera",
        description = "Turns off the slow scrolling camera when swinging with grapple beam (0xDBDAA: 01 → 00).",
        enabled = false, writes = mutableListOf(PatchWrite(0xDBDAA, listOf(0x00)))),
    SmPatch(id = "hex_fast_run_speed", name = "Faster Running Speed",
        description = "Increases default running acceleration and max speed (0x81F64: 30 02 → 50 04).",
        enabled = false, writes = mutableListOf(PatchWrite(0x81F64, listOf(0x50, 0x04)))),
    SmPatch(id = "hex_higher_jump", name = "Higher Jump",
        description = "Increases standard jump height (0x81EB9: 04 → 05).",
        enabled = false, writes = mutableListOf(PatchWrite(0x81EB9, listOf(0x05)))),
    SmPatch(id = "hex_no_suit_flash", name = "No Suit Collection Flash",
        description = "Varia/Gravity suits collect like regular items without the flash and sound (0x20717: NOP×4).",
        enabled = false, writes = mutableListOf(PatchWrite(0x20717, listOf(0xEA, 0xEA, 0xEA, 0xEA)))),
)

/**
 * Ceres escape timer LDA operand at $80:9E0D.
 * The 2-byte immediate operand stores seconds (BCD) in the low byte and minutes (BCD) in the high byte.
 * Vanilla = $0100 → 1 min 0 sec = 60s.
 */
const val CERES_TIMER_OPERAND_SNES = 0x809E0E

/** Config patch: Ceres escape time in seconds. */
val CERES_ESCAPE_PATCH = SmPatch(
    id = "config_ceres_escape_time",
    name = "Ceres Escape Time",
    description = "Sets the Ceres station escape timer in seconds (vanilla: 60). Override when enabled.",
    enabled = false,
    writes = mutableListOf(),
    configType = "ceres_escape_seconds",
    configValue = 60
)

/** Config patch: Beam Damage overrides. Stores per-beam damage values in configData. */
val BEAM_DAMAGE_PATCH = SmPatch(
    id = "config_beam_damage",
    name = "Beam Damage Override",
    description = "Override uncharged and charged beam damage values for all beam types.",
    enabled = false,
    writes = mutableListOf(),
    configType = "beam_damage"
)

/** Legacy/superseded patch IDs — removed on seed to avoid duplicates from old configs. */
internal val LEGACY_PATCH_IDS = setOf(
    "respin", "fast_doors", "no_fanfare", "blue_speed_air", "no_walljump_kick", "instant_stop",
    "no_beeping", "energy_free_shinesparks", "fast_saves", "enable_moonwalk", "skip_ceres", "fast_mb_cutscene",
    "hex_no_spin_speed_loss", "hex_keep_blue_speed", "hex_no_walljump_kick", "hex_no_skid",
    "hex_faster_charged_shots_demo",
)
