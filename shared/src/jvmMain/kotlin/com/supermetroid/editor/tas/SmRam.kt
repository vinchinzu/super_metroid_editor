package com.supermetroid.editor.tas

import kotlinx.serialization.Serializable

/**
 * Super Metroid WRAM map and snapshot parsing.
 *
 * Offsets are into SNES WRAM as exposed by libretro's RETRO_MEMORY_SYSTEM_RAM
 * (bank $7E, so offset 0x0AF6 == $7E:0AF6). They mirror
 * `custom_integrations/SuperMetroid-Snes/data.json` in the Python RL stack —
 * keep the two in sync.
 */
object SmRam {
    // Samus
    const val SAMUS_X = 0x0AF6
    const val SAMUS_Y = 0x0AFA
    const val SAMUS_POSE = 0x0A1C
    const val VELOCITY_X = 0x0B42          // signed
    const val VELOCITY_Y = 0x0B2E          // signed
    const val HEALTH = 0x09C2
    const val MAX_HEALTH = 0x09C4
    const val MISSILES = 0x09C6
    const val MAX_MISSILES = 0x09C8
    const val SELECTED_ITEM = 0x09D2
    const val COLLECTED_ITEMS = 0x09A4     // bit flags
    const val EQUIPPED_ITEMS = 0x09A2
    const val COLLECTED_BEAMS = 0x09A8
    const val EQUIPPED_BEAMS = 0x09A6

    // Room / world
    const val ROOM_ID = 0x079B             // room header pointer (bank $8F)
    const val ROOM_X = 0x0B12              // layer-1 scroll X
    const val ROOM_Y = 0x0B16
    const val GAME_STATE = 0x0998          // 0x08 = normal gameplay
    const val DOOR_TRANSITION = 0x0797
    const val TRANSITION_DIRECTION = 0x0791

    // Timing
    const val IGT_FRAMES = 0x09DA          // in-game time, 60ths of a second
    const val IGT_SECONDS = 0x09DC
    const val IGT_MINUTES = 0x09DE
    const val IGT_HOURS = 0x09E0
    const val TIMER_TYPE = 0x0943          // escape/Ceres countdown active
    const val ESCAPE_TIMER_FRAMES = 0x0945 // u1
    const val ESCAPE_TIMER_SECONDS = 0x0946
    const val ESCAPE_TIMER_MINUTES = 0x0947

    // Combat
    const val ENEMY0_X = 0x0F7A            // enemy slots are 0x40 bytes apart
    const val ENEMY0_Y = 0x0F7E
    const val ENEMY0_HP = 0x0F8C
    const val ENEMY_SLOT_STRIDE = 0x40
    const val ENEMY_SLOT_COUNT = 8
    const val NUM_ENEMIES = 0x0E4E
    const val ENEMIES_KILLED = 0x0E50
    const val SHOT_COOLDOWN = 0x0CCC
    const val BEAM_CHARGE = 0x0CD0

    // Input mirrors (what the game itself sampled)
    const val CONTROLLER_INPUT = 0x008B
    const val CONTROLLER_NEW_BUTTONS = 0x008F

    /** Bytes of WRAM needed to parse a full [SmSnapshot] (last enemy slot ends at 0x114E). */
    const val SNAPSHOT_SIZE = 0x1200

    /** Game state values of interest ($0998). */
    const val GAME_STATE_GAMEPLAY = 0x08
    const val GAME_STATE_DOOR_TRANSITION = 0x0B
    const val GAME_STATE_GAME_OVER = 0x19

    fun word(wram: ByteArray, offset: Int): Int =
        (wram[offset].toInt() and 0xFF) or ((wram[offset + 1].toInt() and 0xFF) shl 8)

    fun signedWord(wram: ByteArray, offset: Int): Int = word(wram, offset).toShort().toInt()

    fun byte(wram: ByteArray, offset: Int): Int = wram[offset].toInt() and 0xFF

    /** Parse a snapshot from a WRAM read of at least [SNAPSHOT_SIZE] bytes at offset 0. */
    fun parse(wram: ByteArray): SmSnapshot {
        require(wram.size >= SNAPSHOT_SIZE) { "Need $SNAPSHOT_SIZE bytes of WRAM, got ${wram.size}" }
        val enemies = (0 until ENEMY_SLOT_COUNT).map { slot ->
            val base = slot * ENEMY_SLOT_STRIDE
            SmEnemy(
                x = word(wram, ENEMY0_X + base),
                y = word(wram, ENEMY0_Y + base),
                hp = word(wram, ENEMY0_HP + base),
            )
        }
        return SmSnapshot(
            samusX = word(wram, SAMUS_X),
            samusY = word(wram, SAMUS_Y),
            samusPose = word(wram, SAMUS_POSE),
            velocityX = signedWord(wram, VELOCITY_X),
            velocityY = signedWord(wram, VELOCITY_Y),
            health = word(wram, HEALTH),
            maxHealth = word(wram, MAX_HEALTH),
            missiles = word(wram, MISSILES),
            maxMissiles = word(wram, MAX_MISSILES),
            collectedItems = word(wram, COLLECTED_ITEMS),
            equippedItems = word(wram, EQUIPPED_ITEMS),
            collectedBeams = word(wram, COLLECTED_BEAMS),
            equippedBeams = word(wram, EQUIPPED_BEAMS),
            roomId = word(wram, ROOM_ID),
            gameState = word(wram, GAME_STATE),
            doorTransition = word(wram, DOOR_TRANSITION) != 0,
            igtFrames = word(wram, IGT_FRAMES),
            igtSeconds = word(wram, IGT_SECONDS),
            igtMinutes = word(wram, IGT_MINUTES),
            igtHours = word(wram, IGT_HOURS),
            enemies = enemies,
            enemiesKilled = word(wram, ENEMIES_KILLED),
        )
    }
}

@Serializable
data class SmEnemy(val x: Int, val y: Int, val hp: Int)

/** A parsed view of game state at one frame — the observation for models and evaluators. */
@Serializable
data class SmSnapshot(
    val samusX: Int = 0,
    val samusY: Int = 0,
    val samusPose: Int = 0,
    val velocityX: Int = 0,
    val velocityY: Int = 0,
    val health: Int = 0,
    val maxHealth: Int = 0,
    val missiles: Int = 0,
    val maxMissiles: Int = 0,
    val collectedItems: Int = 0,
    val equippedItems: Int = 0,
    val collectedBeams: Int = 0,
    val equippedBeams: Int = 0,
    val roomId: Int = 0,
    val gameState: Int = 0,
    val doorTransition: Boolean = false,
    val igtFrames: Int = 0,
    val igtSeconds: Int = 0,
    val igtMinutes: Int = 0,
    val igtHours: Int = 0,
    val enemies: List<SmEnemy> = emptyList(),
    val enemiesKilled: Int = 0,
) {
    val dead: Boolean get() = health == 0 && gameState >= 0x13
    val inGameplay: Boolean get() = gameState == SmRam.GAME_STATE_GAMEPLAY

    /** Total in-game time in frames (the speedrun clock). */
    val igtTotalFrames: Long
        get() = igtFrames + 60L * (igtSeconds + 60L * (igtMinutes + 60L * igtHours))
}
