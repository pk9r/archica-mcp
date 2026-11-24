package com.archica.mcp.blueprint

import com.archica.mcp.context.BlockPos3i
import com.archica.mcp.context.CardinalDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlueprintModelsAndTransformTest {
    private val catalog = FakeBlockCatalog()

    @Test
    fun `block spec parses bare id and bracketed properties canonically`() {
        val bare = BlockSpec.parse("minecraft:oak_planks")
        assertEquals("minecraft:oak_planks", bare.blockId)
        assertTrue(bare.properties.isEmpty())
        assertEquals("minecraft:oak_planks", bare.format())

        val stated = BlockSpec.parse(" minecraft:oak_stairs[half=bottom, facing=north] ")
        assertEquals("minecraft:oak_stairs", stated.blockId)
        assertEquals(mapOf("half" to "bottom", "facing" to "north"), stated.properties)
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom]", stated.format())
    }

    @Test
    fun `block catalog rejects unknown blocks and invalid property values`() {
        val unknown = assertFailsWith<BlueprintException> {
            catalog.parseAndResolve("minecraft:nonexistent_block")
        }
        assertEquals(BlueprintErrorCode.INVALID_BLOCK_ID, unknown.code)

        val badPropValue = assertFailsWith<BlueprintException> {
            catalog.parseAndResolve("minecraft:oak_stairs[half=sideways]")
        }
        assertEquals(BlueprintErrorCode.INVALID_BLOCK_ID, badPropValue.code)

        val badSyntax = assertFailsWith<BlueprintException> {
            BlockSpec.parse("oak_planks")
        }
        assertEquals(BlueprintErrorCode.INVALID_BLOCK_ID, badSyntax.code)
    }

    @Test
    fun `block spec matcher handles bare target and property filtered target`() {
        val voxel = BlockSpec("minecraft:oak_stairs", mapOf("facing" to "east", "half" to "bottom"))
        assertTrue(BlockSpec("minecraft:oak_stairs").matches(voxel))
        assertTrue(BlockSpec("minecraft:oak_stairs", mapOf("facing" to "east")).matches(voxel))
        assertFalse(BlockSpec("minecraft:oak_stairs", mapOf("facing" to "west")).matches(voxel))
    }

    @Test
    fun `facing transform rotates local forward coordinates and directional blockstates`() {
        val origin = BlockPos3i(100, 64, 100)
        val localForward = BlockPos3i(0, 0, 2)
        val localSpec = BlockSpec("minecraft:oak_stairs", mapOf("facing" to "north", "half" to "bottom"))

        assertEquals(BlockPos3i(100, 64, 98), BlueprintTransform.toWorldPos(localForward, origin, CardinalDirection.NORTH))
        assertEquals(BlockPos3i(102, 64, 100), BlueprintTransform.toWorldPos(localForward, origin, CardinalDirection.EAST))
        assertEquals(BlockPos3i(100, 64, 102), BlueprintTransform.toWorldPos(localForward, origin, CardinalDirection.SOUTH))
        assertEquals(BlockPos3i(98, 64, 100), BlueprintTransform.toWorldPos(localForward, origin, CardinalDirection.WEST))

        assertEquals("north", BlueprintTransform.transformBlockSpec(localSpec, CardinalDirection.NORTH).properties["facing"])
        assertEquals("east", BlueprintTransform.transformBlockSpec(localSpec, CardinalDirection.EAST).properties["facing"])
        assertEquals("south", BlueprintTransform.transformBlockSpec(localSpec, CardinalDirection.SOUTH).properties["facing"])
        assertEquals("west", BlueprintTransform.transformBlockSpec(localSpec, CardinalDirection.WEST).properties["facing"])

        val logX = BlockSpec("minecraft:oak_log", mapOf("axis" to "x"))
        assertEquals("z", BlueprintTransform.transformBlockSpec(logX, CardinalDirection.EAST).properties["axis"])
        assertEquals("x", BlueprintTransform.transformBlockSpec(logX, CardinalDirection.SOUTH).properties["axis"])
    }

    @Test
    fun `auto orienter infers stair facing log axis and door halves`() {
        val stairs = catalog.parseAndResolve("minecraft:oak_stairs")
        val orientedStair = BlockAutoOrienter.orientStairs(stairs, towardX = 0, towardZ = 3)
        assertEquals("north", orientedStair.properties["facing"])
        assertEquals("bottom", orientedStair.properties["half"])

        val log = catalog.parseAndResolve("minecraft:oak_log")
        assertEquals("y", BlockAutoOrienter.orientLog(log, dx = 0, dy = 4, dz = 0).properties["axis"])
        assertEquals("x", BlockAutoOrienter.orientLog(log, dx = 5, dy = 0, dz = 1).properties["axis"])
        assertEquals("z", BlockAutoOrienter.orientLog(log, dx = 1, dy = 0, dz = 5).properties["axis"])

        val door = catalog.parseAndResolve("minecraft:oak_door")
        val lower = BlockAutoOrienter.orientDoorHalf(door, half = "lower", inferredFacing = "east")
        assertEquals(mapOf("facing" to "east", "half" to "lower", "hinge" to "left", "open" to "false"), lower.properties)
    }
}
