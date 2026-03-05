package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

/**
 * Verifies the GRAPHADR (Graphics Address) field in species headers
 * correctly points to compressed tile data for enemy sprites.
 */
class EnemyTileScanTest {

    private fun loadTestRom(): RomParser? {
        val paths = listOf(
            "/Users/kenny/code/super_metroid_dev/test-resources/Super Metroid (JU) [!].smc",
            "test-resources/Super Metroid (JU) [!].smc"
        )
        for (p in paths) {
            val f = File(p)
            if (f.exists()) return RomParser.loadRom(f.absolutePath)
        }
        return null
    }

    @Test
    fun `GRAPHADR decompresses to at least tileDataSize bytes for all editor enemies`() {
        val parser = loadTestRom() ?: return
        val rom = parser.getRomData()

        val enemies = listOf(
            0xDCFF to "Zoomer",
            0xDC7F to "Zeela",
            0xD93F to "Sidehopper",
            0xD7FF to "Skree",
            0xCFFF to "Cacatac",
        )

        for ((speciesId, name) in enemies) {
            val stats = EnemySpriteGraphics.readSpeciesStats(parser, speciesId)
            assertNotNull(stats, "$name: stats should not be null")
            val tileDataSize = stats!!.first
            assertTrue(tileDataSize > 0, "$name: tileDataSize should be positive")

            val block = EnemySpriteGraphics.readGraphicsBlock(parser, speciesId)
            assertNotNull(block, "$name: GRAPHADR block should not be null")

            val data = parser.decompressLZ5AtPc(block!!.pcAddress)
            assertTrue(data.size >= tileDataSize,
                "$name: GRAPHADR decompresses to ${data.size} bytes, need at least $tileDataSize")

            val truncated = data.copyOf(tileDataSize)
            val nonZero = truncated.count { it.toInt() != 0 }
            assertTrue(nonZero > tileDataSize / 10,
                "$name: tile data should have significant non-zero content ($nonZero/$tileDataSize)")

            val snesHex = "\$${(block.snesAddress shr 16).toString(16).uppercase()}:" +
                "${(block.snesAddress and 0xFFFF).toString(16).uppercase().padStart(4, '0')}"
            println("$name: GRAPHADR=$snesHex, decompressed=${data.size}, tileDataSize=$tileDataSize, nonZero=$nonZero")
        }
    }
}
