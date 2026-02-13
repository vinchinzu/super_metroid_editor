package com.supermetroid.editor.data

/**
 * Represents a Super Metroid room with its header data and level data.
 * Based on Metroid Construction wiki room data format.
 */
data class Room(
    val roomId: Int,  // Room ID in hex (e.g., 0x91F8)
    val name: String,
    val handle: String,
    
    // Room Header (38 bytes total)
    val index: Int,           // Byte 0: Room index
    val area: Int,            // Byte 1: Area (0=Crateria, 1=Brinstar, etc.)
    val mapX: Int,            // Byte 2: X position on minimap
    val mapY: Int,            // Byte 3: Y position on minimap
    val width: Int,           // Byte 4: Room width in screens
    val height: Int,          // Byte 5: Room height in screens
    val scrollX: Int,         // Byte 6: X scroll type
    val scrollY: Int,         // Byte 7: Y scroll type
    val specialGfxBitflag: Int, // Byte 8: Special graphics bitflag
    val doors: Int,           // Bytes 9-10: Door pointer
    val roomState: Int,       // Bytes 11-12: Room state pointer
    val roomDown: Int,        // Bytes 13-14: Room down pointer
    val roomUp: Int,          // Bytes 15-16: Room up pointer
    val roomLeft: Int,        // Bytes 17-18: Room left pointer
    val roomRight: Int,       // Bytes 19-20: Room right pointer
    val roomDownScroll: Int, // Bytes 21-22: Room down scroll
    val roomUpScroll: Int,   // Bytes 23-24: Room up scroll
    val roomLeftScroll: Int, // Bytes 25-26: Room left scroll
    val roomRightScroll: Int,// Bytes 27-28: Room right scroll
    val unused1: Int,         // Bytes 29-30: Unused
    val mainAsm: Int,        // Bytes 31-32: Main ASM pointer
    val plmSet: Int,          // Bytes 33-34: PLM set pointer
    val bgData: Int,          // Bytes 35-36: Background data pointer
    val roomSetupAsm: Int,   // Bytes 37-38: Room setup ASM pointer
    
    // Level data (decompressed tile data)
    val levelData: ByteArray? = null,
    val levelDataSize: Int = 0
) {
    val tilesetId: Int
        get() = (specialGfxBitflag and 0x0F) // Lower 4 bits
    
    val roomSize: Pair<Int, Int>
        get() = Pair(width, height)
}
