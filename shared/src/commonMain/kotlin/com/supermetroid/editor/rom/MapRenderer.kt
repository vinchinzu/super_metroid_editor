package com.supermetroid.editor.rom

import com.supermetroid.editor.data.Room

/**
 * Renders Super Metroid room maps.
 * 
 * V1: Renders a visual grid showing the room structure with screen boundaries,
 * area-based coloring, and room metadata overlay.
 * 
 * Future: Full tile-based rendering with LZ2 decompression and palette lookup.
 */
class MapRenderer(private val romParser: RomParser) {
    
    companion object {
        private const val SCREEN_PIXELS = 256  // Each screen is 256x256 pixels (16x16 tiles of 16x16)
        private const val TILE_SIZE = 16       // Block/metatile size in pixels
        private const val TILES_PER_SCREEN = 16 // 16 tiles per screen edge
        private const val RENDER_SCALE = 1     // 1:1 pixel scale
    }
    
    // Area colors (ARGB) — matching the game's area themes
    private val areaColors = mapOf(
        0 to intArrayOf(0xFF2A4858.toInt(), 0xFF1A3040.toInt(), 0xFF3A6070.toInt()), // Crateria (blue-gray)
        1 to intArrayOf(0xFF2A5828.toInt(), 0xFF1A4018.toInt(), 0xFF3A7038.toInt()), // Brinstar (green)
        2 to intArrayOf(0xFF6A2828.toInt(), 0xFF4A1818.toInt(), 0xFF8A3838.toInt()), // Norfair (red)
        3 to intArrayOf(0xFF3A3A5A.toInt(), 0xFF2A2A4A.toInt(), 0xFF4A4A6A.toInt()), // Wrecked Ship (purple)
        4 to intArrayOf(0xFF28486A.toInt(), 0xFF18384A.toInt(), 0xFF38588A.toInt()), // Maridia (blue)
        5 to intArrayOf(0xFF5A4828.toInt(), 0xFF4A3818.toInt(), 0xFF6A5838.toInt()), // Tourian (brown)
        6 to intArrayOf(0xFF5A5A5A.toInt(), 0xFF3A3A3A.toInt(), 0xFF7A7A7A.toInt()), // Ceres (gray)
    )
    
    /**
     * Render room to pixel data — V1 grid visualization
     */
    fun renderRoom(room: Room): RoomRenderData? {
        val screenWidth = room.width   // Width in screens
        val screenHeight = room.height // Height in screens
        
        val pixelWidth = screenWidth * SCREEN_PIXELS
        val pixelHeight = screenHeight * SCREEN_PIXELS
        
        if (pixelWidth <= 0 || pixelHeight <= 0) return null
        
        val pixels = IntArray(pixelWidth * pixelHeight)
        val colors = areaColors[room.area] ?: areaColors[0]!!
        val bgColor = colors[0]
        val darkColor = colors[1]
        val lightColor = colors[2]
        
        // Fill background
        pixels.fill(bgColor)
        
        // Draw tile grid pattern within each screen
        for (sy in 0 until screenHeight) {
            for (sx in 0 until screenWidth) {
                drawScreenBlock(pixels, pixelWidth, pixelHeight,
                    sx * SCREEN_PIXELS, sy * SCREEN_PIXELS,
                    bgColor, darkColor, lightColor)
            }
        }
        
        // Draw screen boundary grid lines
        for (sy in 0..screenHeight) {
            val y = sy * SCREEN_PIXELS
            if (y < pixelHeight) {
                for (x in 0 until pixelWidth) {
                    val idx = y * pixelWidth + x
                    if (idx < pixels.size) {
                        pixels[idx] = 0xFFFFFFFF.toInt() // White grid lines
                    }
                }
            }
            // Draw thicker line (2px)
            val y2 = sy * SCREEN_PIXELS - 1
            if (y2 in 0 until pixelHeight) {
                for (x in 0 until pixelWidth) {
                    val idx = y2 * pixelWidth + x
                    if (idx < pixels.size) {
                        pixels[idx] = 0xFFFFFFFF.toInt()
                    }
                }
            }
        }
        
        for (sx in 0..screenWidth) {
            val x = sx * SCREEN_PIXELS
            if (x < pixelWidth) {
                for (y in 0 until pixelHeight) {
                    val idx = y * pixelWidth + x
                    if (idx < pixels.size) {
                        pixels[idx] = 0xFFFFFFFF.toInt()
                    }
                }
            }
            val x2 = sx * SCREEN_PIXELS - 1
            if (x2 in 0 until pixelWidth) {
                for (y in 0 until pixelHeight) {
                    val idx = y * pixelWidth + x2
                    if (idx < pixels.size) {
                        pixels[idx] = 0xFFFFFFFF.toInt()
                    }
                }
            }
        }
        
        // Draw door indicator if we have a door pointer
        if (room.doors != 0) {
            drawDoorIndicator(pixels, pixelWidth, pixelHeight, lightColor)
        }
        
        return RoomRenderData(pixelWidth, pixelHeight, pixels)
    }
    
    /**
     * Draw a checkerboard-like pattern inside a screen block to give it texture
     */
    private fun drawScreenBlock(
        pixels: IntArray, totalWidth: Int, totalHeight: Int,
        startX: Int, startY: Int,
        bgColor: Int, darkColor: Int, lightColor: Int
    ) {
        for (ty in 0 until TILES_PER_SCREEN) {
            for (tx in 0 until TILES_PER_SCREEN) {
                val tileX = startX + tx * TILE_SIZE
                val tileY = startY + ty * TILE_SIZE
                
                // Checkerboard pattern
                val isDark = (tx + ty) % 2 == 0
                val tileColor = if (isDark) bgColor else darkColor
                
                // Fill tile area
                for (py in 0 until TILE_SIZE) {
                    for (px in 0 until TILE_SIZE) {
                        val x = tileX + px
                        val y = tileY + py
                        if (x in 0 until totalWidth && y in 0 until totalHeight) {
                            val idx = y * totalWidth + x
                            if (idx < pixels.size) {
                                pixels[idx] = tileColor
                            }
                        }
                    }
                }
                
                // Draw subtle tile border
                for (px in 0 until TILE_SIZE) {
                    val x = tileX + px
                    val y = tileY
                    if (x in 0 until totalWidth && y in 0 until totalHeight) {
                        val idx = y * totalWidth + x
                        if (idx < pixels.size) {
                            pixels[idx] = blendColor(pixels[idx], 0x20FFFFFF)
                        }
                    }
                }
                for (py in 0 until TILE_SIZE) {
                    val x = tileX
                    val y = tileY + py
                    if (x in 0 until totalWidth && y in 0 until totalHeight) {
                        val idx = y * totalWidth + x
                        if (idx < pixels.size) {
                            pixels[idx] = blendColor(pixels[idx], 0x20FFFFFF)
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Draw a small door indicator in the corner of the room
     */
    private fun drawDoorIndicator(pixels: IntArray, width: Int, height: Int, color: Int) {
        val indicatorSize = 8
        val margin = 10
        
        for (py in 0 until indicatorSize) {
            for (px in 0 until indicatorSize) {
                val x = margin + px
                val y = margin + py
                if (x in 0 until width && y in 0 until height) {
                    val idx = y * width + x
                    if (idx < pixels.size) {
                        pixels[idx] = color
                    }
                }
            }
        }
    }
    
    /**
     * Simple alpha blend
     */
    private fun blendColor(base: Int, overlay: Int): Int {
        val alpha = (overlay ushr 24) and 0xFF
        if (alpha == 0) return base
        
        val invAlpha = 255 - alpha
        val r = (((overlay shr 16) and 0xFF) * alpha + ((base shr 16) and 0xFF) * invAlpha) / 255
        val g = (((overlay shr 8) and 0xFF) * alpha + ((base shr 8) and 0xFF) * invAlpha) / 255
        val b = ((overlay and 0xFF) * alpha + (base and 0xFF) * invAlpha) / 255
        
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/**
 * Render data for a room
 */
data class RoomRenderData(
    val width: Int,
    val height: Int,
    val pixels: IntArray
)
