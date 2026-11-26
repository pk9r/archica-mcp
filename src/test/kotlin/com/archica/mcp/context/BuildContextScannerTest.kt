package com.archica.mcp.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildContextScannerTest {
    @Test
    fun `default outdoor scan uses radius 12 and omits obstacles and inventory`() {
        val world = FakeWorldContextSource(
            inventory = listOf(InventoryEntry("minecraft:oak_planks", 64)),
        )
        world.fillFloor(-20, 20, -20, 20, y = 64)

        var inventoryCalls = 0
        val trackingWorld = object : WorldContextSource by world {
            override fun getInventoryMaterials(): List<InventoryEntry> {
                inventoryCalls++
                return world.getInventoryMaterials()
            }
        }

        val result = BuildContextScanner(trackingWorld).scan()
        assertEquals(12, result.radius)
        assertEquals(25, result.gridSize)
        assertEquals(25, result.rows.size)
        result.rows.forEach { assertEquals(25, it.cells.size) }
        assertFalse(result.inventoryIncluded)
        assertTrue(result.inventoryMaterials.isEmpty())
        assertEquals(0, inventoryCalls)

        val text = result.formatText()
        assertTrue(text.contains("[PLAYER]"))
        assertFalse(text.contains("[INVENTORY_MATERIALS]"))
        assertTrue(text.contains("[SURFACE_LEGEND]"))
        assertTrue(text.contains("[TERRAIN_GRID origin=(-12, -12) size=25x25 ref_y=65]"))
        assertFalse(text.contains("[OBSTACLES]"))
    }

    @Test
    fun `canopies remain surface blocks while flowers and thin snow are pass-through`() {
        val world = FakeWorldContextSource()
        world.fillFloor(-5, 5, -5, 5, y = 64, blockId = "minecraft:grass_block")
        world.setBlock(1, 65, 0, "minecraft:poppy")
        world.setBlock(2, 65, 0, "minecraft:snow", mapOf("layers" to "1"))
        world.setBlock(3, 65, 0, "minecraft:snow", mapOf("layers" to "2"))
        world.setBlock(-1, 68, 0, "minecraft:oak_leaves", mapOf("distance" to "2"))

        val result = BuildContextScanner(world).scan(BuildContextRequest(radius = 4))
        val z0Row = result.rows.first { it.z == 0 }
        // x runs from -4 to 4 -> indices: -1 is index 3, 1 is index 5, 2 is index 6, 3 is index 7
        val leafSymbol = result.legend.symbolFor("minecraft:oak_leaves")
        val grassSymbol = result.legend.symbolFor("minecraft:grass_block")
        val snowSymbol = result.legend.symbolFor("minecraft:snow")

        assertEquals("${leafSymbol}+3", z0Row.cells[3]) // y=68 vs ref_y=65
        assertEquals("${grassSymbol}-1", z0Row.cells[5]) // poppy skipped -> grass at 64
        assertEquals("${grassSymbol}-1", z0Row.cells[6]) // 1-layer snow skipped -> grass at 64
        assertEquals("${snowSymbol}0", z0Row.cells[7]) // 2-layer snow kept -> y=65
    }

    @Test
    fun `legend guarantees 1-to-1 unique symbols and aligned non-RLE grid`() {
        val world = FakeWorldContextSource()
        world.fillFloor(-4, 4, -4, 4, y = 65, blockId = "minecraft:grass_block")
        world.setBlock(-2, 66, 0, "minecraft:cobblestone")
        world.setBlock(-1, 66, 0, "minecraft:stone")
        world.setBlock(0, 65, 0, "minecraft:air")
        world.setBlock(0, 64, 0, "minecraft:water")
        // Leave (2, 0) completely empty (out-of-window)
        world.setBlock(2, 65, 0, "minecraft:air")

        val result = BuildContextScanner(world).scan(BuildContextRequest(radius = 4))
        val symbols = result.legend.blockToSymbol.values
        assertEquals(symbols.size, symbols.distinct().size)
        assertTrue(symbols.none { it.isDigit() || it in setOf('+', '-', '?') || it.isWhitespace() })

        val grassSym = result.legend.symbolFor("minecraft:grass_block")
        val cobbleSym = result.legend.symbolFor("minecraft:cobblestone")
        val stoneSym = result.legend.symbolFor("minecraft:stone")
        val waterSym = result.legend.symbolFor("minecraft:water")

        val rowZ0 = result.rows.first { it.z == 0 }
        assertEquals("${cobbleSym}+1", rowZ0.cells[2])
        assertEquals("${stoneSym}+1", rowZ0.cells[3])
        assertEquals("${waterSym}-1", rowZ0.cells[4])
        assertEquals("${grassSym}0", rowZ0.cells[5])
        assertEquals("?", rowZ0.cells[6])

        val text = result.formatText()
        assertFalse(text.contains("*"))
        // Every TERRAIN_GRID data row should have uniform line length when columns are padded
        val gridLines = text.substringAfter("[TERRAIN_GRID").lines().drop(1)
        assertEquals(1, gridLines.map { it.length }.distinct().size)
    }

    @Test
    fun `suggested origin snaps to nearby solid ground when look target hits water or leaves`() {
        val world = FakeWorldContextSource(
            player = PlayerSnapshot(
                pos = BlockPos3i(0, 65, 0),
                yaw = 180f, // NORTH -> target around (0, -10)
                pitch = 0f,
                biome = "minecraft:plains",
                dimension = "minecraft:overworld",
                gameMode = "survival",
            ),
        )
        world.fillFloor(-16, 16, -16, 16, y = 64, blockId = "minecraft:grass_block")
        // Cover target (0, -10) and immediate ring with water and oak_leaves
        for (z in -11..-9) {
            for (x in -1..1) {
                world.setBlock(x, 64, z, "minecraft:water")
            }
        }
        world.setBlock(0, 68, -10, "minecraft:oak_leaves")

        val result = BuildContextScanner(world).scan(BuildContextRequest(radius = 12))
        // Nearest solid grass_block outside the 3x3 pond around (0, -10) is distance 2 (e.g., (0, -12))
        assertEquals("minecraft:grass_block", result.suggestedOrigin.surfaceBlockId)
        assertEquals(64, result.suggestedOrigin.groundY)
        assertEquals(65, result.suggestedOrigin.pos.y)
        val dx = kotlin.math.abs(result.suggestedOrigin.pos.x - 0)
        val dz = kotlin.math.abs(result.suggestedOrigin.pos.z - (-10))
        assertEquals(2, maxOf(dx, dz))
    }

    @Test
    fun `inventory materials aggregate across slots and sort descending when included`() {
        val world = FakeWorldContextSource(
            inventory = listOf(
                InventoryEntry("minecraft:oak_planks", 64),
                InventoryEntry("minecraft:cobblestone", 32),
                InventoryEntry("minecraft:oak_planks", 64),
                InventoryEntry("minecraft:glass_pane", 32),
            ),
        )
        world.fillFloor(-4, 4, -4, 4, y = 64)

        val result = BuildContextScanner(world).scan(BuildContextRequest(radius = 4, includeInventory = true))
        assertTrue(result.inventoryIncluded)
        assertEquals(
            listOf(
                InventoryEntry("minecraft:oak_planks", 128),
                InventoryEntry("minecraft:cobblestone", 32),
                InventoryEntry("minecraft:glass_pane", 32),
            ),
            result.inventoryMaterials,
        )
        val text = result.formatText()
        assertTrue(text.contains("[INVENTORY_MATERIALS]"))
        assertTrue(text.contains("oak_planks:128, cobblestone:32, glass_pane:32"))

        val emptyWorld = FakeWorldContextSource()
        emptyWorld.fillFloor(-4, 4, -4, 4, y = 64)
        val emptyResult = BuildContextScanner(emptyWorld).scan(BuildContextRequest(radius = 4, includeInventory = true))
        assertTrue(emptyResult.inventoryIncluded)
        assertTrue(emptyResult.formatText().contains("[INVENTORY_MATERIALS]\nnone"))
    }

    @Test
    fun `invalid radius and unloaded world fail with structured errors`() {
        val world = FakeWorldContextSource()
        val scanner = BuildContextScanner(world)

        val tooSmall = assertFailsWith<ContextCompressionException> {
            scanner.scan(BuildContextRequest(radius = 3))
        }
        assertEquals(ContextErrorCode.INVALID_RADIUS, tooSmall.code)

        val tooLarge = assertFailsWith<ContextCompressionException> {
            scanner.scan(BuildContextRequest(radius = 25))
        }
        assertEquals(ContextErrorCode.INVALID_RADIUS, tooLarge.code)

        world.loaded = false
        val noWorld = assertFailsWith<ContextCompressionException> {
            scanner.scan()
        }
        assertEquals(ContextErrorCode.NO_WORLD_LOADED, noWorld.code)
    }
}
