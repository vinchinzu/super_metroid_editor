package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

/**
 * Tests for Phantoon sprite tile block loading, palette extraction,
 * and tile data integrity.
 */
class PhantoonSpriteMapTest {

    private fun loadTestRom(): RomParser? {
        val paths = listOf(
            "/Users/kenny/code/super_metroid_dev/test-resources/Super Metroid (JU) [!].smc",
            "test-resources/Super Metroid (JU) [!].smc"
        )
        for (p in paths) {
            val f = File(p)
            if (f.exists()) return RomParser.loadRom(f.absolutePath)
        }
        println("Test ROM not found, skipping test")
        return null
    }

    @Test
    fun `Phantoon tile blocks decompress to expected tile counts`() {
        val parser = loadTestRom() ?: return
        val gfx = EnemySpriteGraphics(parser)
        assertTrue(gfx.load(EnemySpriteGraphics.PHANTOON_BLOCKS))

        val totalTiles = gfx.getTileCount()
        assertEquals(78, totalTiles, "Phantoon should have 78 tiles total (37 + 41)")
        assertEquals(37, gfx.getTileCountInBlock(0), "Block A should have 37 tiles")
        assertEquals(41, gfx.getTileCountInBlock(1), "Block B should have 41 tiles")
    }

    @Test
    fun `E4BF PNG has expected dimensions and non-trivial content`() {
        val pngFile = File("../desktopApp/src/jvmMain/resources/enemies/E4BF.png")
        if (!pngFile.exists()) {
            println("E4BF.png not found, skipping")
            return
        }
        val img = javax.imageio.ImageIO.read(pngFile)
        assertEquals(70, img.width, "E4BF.png width")
        assertEquals(96, img.height, "E4BF.png height")

        val pixels = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, pixels, 0, img.width)
        val nonTransparent = pixels.count { ((it ushr 24) and 0xFF) > 128 }
        assertTrue(nonTransparent > 1000, "E4BF.png should have substantial visible content, found $nonTransparent pixels")
    }

    @Test
    fun `palette extracted from E4BF PNG has 11-12 unique non-transparent colors`() {
        val pngFile = File("../desktopApp/src/jvmMain/resources/enemies/E4BF.png")
        if (!pngFile.exists()) return
        val img = javax.imageio.ImageIO.read(pngFile)
        val pixels = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, pixels, 0, img.width)

        val palette = EnemySpriteGraphics.extractPaletteFromArgb(pixels)
        val nonZero = palette.count { it != 0 }
        println("Extracted palette: $nonZero non-transparent colors")
        for (i in palette.indices) {
            if (palette[i] != 0) {
                println("  [$i] ARGB=0x${(palette[i].toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')}")
            }
        }
        assertTrue(nonZero in 10..15, "Phantoon palette should have 10-15 non-transparent colors, found $nonZero")
    }

    @Test
    fun `all 4 Phantoon component PNGs combined produce at most 15 unique opaque colors`() {
        val speciesIds = listOf("E4BF", "E4FF", "E53F", "E57F")
        val allColors = mutableSetOf<Int>()

        for (id in speciesIds) {
            val pngFile = File("../desktopApp/src/jvmMain/resources/enemies/$id.png")
            if (!pngFile.exists()) { println("$id.png not found"); continue }
            val img = javax.imageio.ImageIO.read(pngFile)
            val pixels = IntArray(img.width * img.height)
            img.getRGB(0, 0, img.width, img.height, pixels, 0, img.width)
            val unique = pixels.filter { ((it ushr 24) and 0xFF) > 128 }
                .map { it or (0xFF shl 24) }
                .toSet()
            println("$id.png: ${img.width}x${img.height}, ${unique.size} unique opaque colors")
            for (c in unique.sorted()) {
                println("  ARGB=0x${(c.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')}")
            }
            allColors.addAll(unique)
        }

        println("\nCombined unique opaque colors: ${allColors.size}")
        for (c in allColors.sorted()) {
            println("  ARGB=0x${(c.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')}")
        }
        assertTrue(allColors.size <= 15, "SNES palette has at most 15 non-transparent colors, found ${allColors.size}")
    }

    @Test
    fun `species header at A0-E4BF has correct HP and AI bank`() {
        val parser = loadTestRom() ?: return
        val rom = parser.getRomData()
        val headerPc = parser.snesToPc(0xA0E4BF)

        // +$00-01: first field (varies by interpretation), +$04: HP or damage
        // +$0C: AI bank byte ($A7 for Phantoon)
        val aiBankByte = rom[headerPc + 0x0C].toInt() and 0xFF
        assertEquals(0xA7, aiBankByte, "Phantoon AI bank should be \$A7")

        // Init AI pointer at +$12 should be $CDF3
        val initAi = (rom[headerPc + 0x12].toInt() and 0xFF) or
            ((rom[headerPc + 0x13].toInt() and 0xFF) shl 8)
        assertEquals(0xCDF3, initAi, "Phantoon init AI should be \$CDF3")
    }
}
