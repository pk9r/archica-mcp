package com.archica.mcp.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VolumeScannerTest {
    @Test
    fun `omits empty Y slices and RLE compresses Z rows`() {
        val world = FakeWorldContextSource()
        // Volume: x=116..120 (5), y=65..67 (3), z=-54..-52 (3)
        world.setBlock(116, 65, -53, "minecraft:cobblestone")
        world.setBlock(117, 65, -53, "minecraft:cobblestone")
        world.setBlock(118, 65, -53, "minecraft:cobblestone")

        world.setBlock(116, 66, -53, "minecraft:cobblestone")
        world.setBlock(117, 66, -53, "minecraft:oak_planks")
        world.setBlock(118, 66, -53, "minecraft:cobblestone")
        // y=67 remains all air

        val result = VolumeScanner(world).scan(
            ScanVolumeRequest(
                min = BlockPos3i(116, 65, -54),
                max = BlockPos3i(120, 67, -52),
            ),
        )

        assertEquals(listOf("minecraft:air", "minecraft:cobblestone", "minecraft:oak_planks"), result.palette)
        assertEquals(listOf(65, 66), result.layers.map { it.y })

        val text = result.formatText()
        assertTrue(text.contains("[VOLUME min=(116, 65, -54) max=(120, 67, -52) size=5x3x3]"))
        assertTrue(text.contains("0=minecraft:air  1=minecraft:cobblestone  2=minecraft:oak_planks"))
        assertTrue(text.contains("z-53: 3*1, 2*0"))
        assertTrue(text.contains("z-53: 1*1, 1*2, 1*1, 2*0"))
        assertFalse(text.contains("y=67:"))
    }

    @Test
    fun `include_states controls compact blockstate palette filtering`() {
        val world = FakeWorldContextSource()
        world.setBlock(
            0,
            65,
            0,
            "minecraft:oak_stairs",
            mapOf("facing" to "east", "half" to "bottom", "waterlogged" to "false"),
        )
        world.setBlock(
            1,
            65,
            0,
            "minecraft:oak_stairs",
            mapOf("facing" to "west", "half" to "bottom", "waterlogged" to "false"),
        )
        world.setBlock(
            2,
            65,
            0,
            "minecraft:oak_leaves",
            mapOf("distance" to "3", "persistent" to "false", "waterlogged" to "false"),
        )

        val scanner = VolumeScanner(world)
        val withoutStates = scanner.scan(
            ScanVolumeRequest(
                min = BlockPos3i(0, 65, 0),
                max = BlockPos3i(2, 65, 0),
                includeStates = false,
            ),
        )
        assertEquals(
            listOf("minecraft:air", "minecraft:oak_stairs", "minecraft:oak_leaves"),
            withoutStates.palette,
        )

        val withStates = scanner.scan(
            ScanVolumeRequest(
                min = BlockPos3i(0, 65, 0),
                max = BlockPos3i(2, 65, 0),
                includeStates = true,
            ),
        )
        assertEquals(
            listOf(
                "minecraft:air",
                "minecraft:oak_stairs[facing=east,half=bottom]",
                "minecraft:oak_stairs[facing=west,half=bottom]",
                "minecraft:oak_leaves",
            ),
            withStates.palette,
        )
    }

    @Test
    fun `rejects oversized volumes, inverted bounds, and unloaded worlds`() {
        val world = FakeWorldContextSource()
        val scanner = VolumeScanner(world)

        val tooLarge = assertFailsWith<ContextCompressionException> {
            scanner.scan(ScanVolumeRequest(BlockPos3i(0, 0, 0), BlockPos3i(24, 23, 23))) // 25*24*24 = 14,400
        }
        assertEquals(ContextErrorCode.VOLUME_TOO_LARGE, tooLarge.code)

        val inverted = assertFailsWith<ContextCompressionException> {
            scanner.scan(ScanVolumeRequest(BlockPos3i(5, 0, 0), BlockPos3i(4, 1, 1)))
        }
        assertEquals(ContextErrorCode.INVALID_BOUNDS, inverted.code)

        world.loaded = false
        val noWorld = assertFailsWith<ContextCompressionException> {
            scanner.scan(ScanVolumeRequest(BlockPos3i(0, 0, 0), BlockPos3i(1, 1, 1)))
        }
        assertEquals(ContextErrorCode.NO_WORLD_LOADED, noWorld.code)
    }
}
