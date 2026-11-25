package com.archica.mcp.blueprint

import com.archica.mcp.context.BlockPos3i
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlueprintRasterizerTest {
    private val rasterizer = BlueprintRasterizer(FakeBlockCatalog())

    @Test
    fun `fill_box normalizes reversed bounds and hollow_box walls_only skips caps`() {
        val fill = rasterizer.rasterize(
            listOf(PrimitiveOp.FillBox(BlockPos3i(4, 2, 4), BlockPos3i(0, 0, 0), "minecraft:stone")),
        )
        assertEquals(75, fill.totalBlocks)
        assertEquals(BlueprintBounds(BlockPos3i(0, 0, 0), BlockPos3i(4, 2, 4)), fill.enclosingBounds())

        val walls = rasterizer.rasterize(
            listOf(PrimitiveOp.HollowBox(BlockPos3i(0, 0, 0), BlockPos3i(3, 2, 3), "minecraft:cobblestone", wallsOnly = true)),
        )
        assertNull(walls.get(BlockPos3i(1, 0, 1)))
        assertNull(walls.get(BlockPos3i(1, 2, 1)))
        assertNotNull(walls.get(BlockPos3i(0, 1, 2)))
        assertEquals(36, walls.totalBlocks)
    }

    @Test
    fun `clear_box and set_block door place lower and upper halves cleanly`() {
        val grid = rasterizer.rasterize(
            listOf(
                PrimitiveOp.HollowBox(BlockPos3i(0, 0, 0), BlockPos3i(6, 4, 6), "minecraft:oak_planks"),
                PrimitiveOp.ClearBox(BlockPos3i(3, 1, 0), BlockPos3i(3, 2, 0)),
                PrimitiveOp.SetBlock(BlockPos3i(3, 1, 0), "minecraft:oak_door"),
            ),
        )
        val lower = grid.get(BlockPos3i(3, 1, 0))
        val upper = grid.get(BlockPos3i(3, 2, 0))
        assertEquals("lower", lower?.properties?.get("half"))
        assertEquals("upper", upper?.properties?.get("half"))
        assertEquals(lower?.properties?.get("facing"), upper?.properties?.get("facing"))
        assertEquals(1, grid.materialCounts()["minecraft:oak_door"])
    }

    @Test
    fun `line rasterizes 3d bresenham and infers log axis`() {
        val pillar = rasterizer.rasterize(
            listOf(PrimitiveOp.Line(BlockPos3i(0, 0, 0), BlockPos3i(0, 4, 0), "minecraft:oak_log")),
        )
        assertEquals(5, pillar.totalBlocks)
        assertEquals("y", pillar.get(BlockPos3i(0, 2, 0))?.properties?.get("axis"))

        val diagonal = rasterizer.rasterize(
            listOf(PrimitiveOp.Line(BlockPos3i(0, 0, 0), BlockPos3i(4, 2, 2), "minecraft:stone")),
        )
        assertEquals(5, diagonal.totalBlocks)
        assertNotNull(diagonal.get(BlockPos3i(0, 0, 0)))
        assertNotNull(diagonal.get(BlockPos3i(4, 2, 2)))
    }

    @Test
    fun `replace_box supports bare target and explicit blockstate filter`() {
        val grid = rasterizer.rasterize(
            listOf(
                PrimitiveOp.SetBlock(BlockPos3i(0, 0, 0), "minecraft:oak_stairs[facing=north,half=bottom]"),
                PrimitiveOp.SetBlock(BlockPos3i(1, 0, 0), "minecraft:oak_stairs[facing=south,half=bottom]"),
                PrimitiveOp.SetBlock(BlockPos3i(2, 0, 0), "minecraft:cobblestone"),
                PrimitiveOp.ReplaceBox(
                    from = BlockPos3i(0, 0, 0),
                    to = BlockPos3i(2, 0, 0),
                    target = "minecraft:oak_stairs[facing=north]",
                    replacement = "minecraft:stone_bricks",
                ),
            ),
        )
        assertEquals("minecraft:stone_bricks", grid.get(BlockPos3i(0, 0, 0))?.blockId)
        assertEquals("minecraft:oak_stairs", grid.get(BlockPos3i(1, 0, 0))?.blockId)
        assertEquals("minecraft:cobblestone", grid.get(BlockPos3i(2, 0, 0))?.blockId)

        val replaceAllStairs = rasterizer.rasterize(
            listOf(
                PrimitiveOp.SetBlock(BlockPos3i(0, 0, 0), "minecraft:oak_stairs[facing=north,half=bottom]"),
                PrimitiveOp.SetBlock(BlockPos3i(1, 0, 0), "minecraft:oak_stairs[facing=south,half=bottom]"),
                PrimitiveOp.ReplaceBox(
                    from = BlockPos3i(0, 0, 0),
                    to = BlockPos3i(1, 0, 0),
                    target = "minecraft:oak_stairs",
                    replacement = "minecraft:air",
                ),
            ),
        )
        assertEquals(0, replaceAllStairs.totalBlocks)
    }

    @Test
    fun `cylinder and dome generate rounded small-radius shells`() {
        val cyl = rasterizer.rasterize(
            listOf(PrimitiveOp.Cylinder(BlockPos3i(0, 0, 0), radius = 3, height = 3, block = "minecraft:stone", hollow = true)),
        )
        assertNull(cyl.get(BlockPos3i(0, 1, 0)))
        assertNotNull(cyl.get(BlockPos3i(3, 1, 0)))
        assertNotNull(cyl.get(BlockPos3i(2, 1, 2)))

        val dome = rasterizer.rasterize(
            listOf(PrimitiveOp.Dome(BlockPos3i(0, 0, 0), radius = 3, block = "minecraft:glass", hollow = true)),
        )
        assertNull(dome.get(BlockPos3i(0, 0, 0)))
        assertNull(dome.get(BlockPos3i(0, 1, 0)))
        assertNotNull(dome.get(BlockPos3i(0, 3, 0)))
        assertNotNull(dome.get(BlockPos3i(3, 0, 0)))
    }

    @Test
    fun `gable_roof builds odd ridge cap and even symmetric peak with overhang`() {
        val oddRoof = rasterizer.rasterize(
            listOf(
                PrimitiveOp.GableRoof(
                    from = BlockPos3i(0, 4, 0),
                    to = BlockPos3i(6, 4, 8),
                    block = "minecraft:oak_stairs",
                    ridgeBlock = "minecraft:oak_slab",
                    ridgeAxis = RidgeAxis.Z,
                    overhang = 1,
                ),
            ),
        )
        val bounds = oddRoof.enclosingBounds()!!
        assertEquals(BlockPos3i(-1, 4, -1), bounds.min)
        assertEquals(BlockPos3i(7, 8, 9), bounds.max)
        assertEquals("east", oddRoof.get(BlockPos3i(-1, 4, 0))?.properties?.get("facing"))
        assertEquals("west", oddRoof.get(BlockPos3i(7, 4, 0))?.properties?.get("facing"))
        assertEquals("minecraft:oak_slab", oddRoof.get(BlockPos3i(3, 8, 4))?.blockId)

        val evenRoof = rasterizer.rasterize(
            listOf(
                PrimitiveOp.GableRoof(
                    from = BlockPos3i(0, 4, 0),
                    to = BlockPos3i(5, 4, 4),
                    block = "minecraft:oak_stairs",
                    ridgeAxis = RidgeAxis.X,
                    overhang = 0,
                ),
            ),
        )
        assertEquals("north", evenRoof.get(BlockPos3i(0, 4, 0))?.properties?.get("facing"))
        assertEquals("south", evenRoof.get(BlockPos3i(0, 4, 4))?.properties?.get("facing"))
        assertEquals("north", evenRoof.get(BlockPos3i(0, 5, 1))?.properties?.get("facing"))
        assertEquals("south", evenRoof.get(BlockPos3i(0, 5, 3))?.properties?.get("facing"))
    }

    @Test
    fun `staircase orients stairs along climb and fills support underneath`() {
        val stairs = rasterizer.rasterize(
            listOf(
                PrimitiveOp.Staircase(
                    from = BlockPos3i(0, 0, 0),
                    to = BlockPos3i(3, 3, 0),
                    block = "minecraft:cobblestone_stairs",
                    width = 2,
                    fillUnder = true,
                ),
            ),
        )
        assertEquals("east", stairs.get(BlockPos3i(2, 2, 0))?.properties?.get("facing"))
        assertEquals("east", stairs.get(BlockPos3i(2, 2, 1))?.properties?.get("facing"))
        assertNotNull(stairs.get(BlockPos3i(2, 0, 0)))
        assertNotNull(stairs.get(BlockPos3i(2, 1, 1)))
        assertEquals(20, stairs.totalBlocks)
    }

    @Test
    fun `repeat_pattern offsets nested operations and enforces count and depth caps`() {
        val posts = rasterizer.rasterize(
            listOf(
                PrimitiveOp.RepeatPattern(
                    op = PrimitiveOp.Line(BlockPos3i(0, 0, 0), BlockPos3i(0, 2, 0), "minecraft:oak_log"),
                    count = 3,
                    step = BlockPos3i(2, 0, 0),
                ),
            ),
        )
        assertEquals(9, posts.totalBlocks)
        assertNotNull(posts.get(BlockPos3i(4, 2, 0)))

        val badCount = assertFailsWith<BlueprintException> {
            rasterizer.validate(
                PrimitiveOp.RepeatPattern(
                    op = PrimitiveOp.SetBlock(BlockPos3i(0, 0, 0), "minecraft:stone"),
                    count = 257,
                    step = BlockPos3i(1, 0, 0),
                ),
            )
        }
        assertEquals(BlueprintErrorCode.INVALID_ARGUMENTS, badCount.code)

        var nested: PrimitiveOp = PrimitiveOp.SetBlock(BlockPos3i(0, 0, 0), "minecraft:stone")
        repeat(5) {
            nested = PrimitiveOp.RepeatPattern(nested, count = 1, step = BlockPos3i(1, 0, 0))
        }
        val badDepth = assertFailsWith<BlueprintException> {
            rasterizer.validate(nested)
        }
        assertEquals(BlueprintErrorCode.INVALID_ARGUMENTS, badDepth.code)
    }

    @Test
    fun `enclosing volume exceeding cap throws VOLUME_TOO_LARGE`() {
        val err = assertFailsWith<BlueprintException> {
            rasterizer.rasterize(
                listOf(PrimitiveOp.FillBox(BlockPos3i(0, 0, 0), BlockPos3i(63, 63, 63), "minecraft:stone")),
            )
        }
        assertEquals(BlueprintErrorCode.VOLUME_TOO_LARGE, err.code)
        assertTrue(err.message.contains("131072"))
    }

    @Test
    fun `connectable panes and fences auto-connect horizontally while preserving explicit properties`() {
        val grid = rasterizer.rasterize(
            listOf(
                PrimitiveOp.SetBlock(BlockPos3i(0, 0, 0), "minecraft:oak_planks"),
                PrimitiveOp.SetBlock(BlockPos3i(2, 0, 0), "minecraft:oak_planks"),
                PrimitiveOp.SetBlock(BlockPos3i(1, 0, 0), "minecraft:glass_pane"),
                PrimitiveOp.SetBlock(BlockPos3i(1, 0, 1), "minecraft:glass_pane"),
                PrimitiveOp.SetBlock(BlockPos3i(5, 0, 5), "minecraft:oak_planks"),
                PrimitiveOp.SetBlock(BlockPos3i(5, 0, 3), "minecraft:oak_planks"),
                PrimitiveOp.SetBlock(BlockPos3i(6, 0, 4), "minecraft:oak_planks"),
                PrimitiveOp.SetBlock(BlockPos3i(4, 0, 4), "minecraft:oak_planks"),
                PrimitiveOp.SetBlock(BlockPos3i(5, 0, 4), "minecraft:oak_fence[north=true,east=false]"),
            ),
        )

        val middlePane = grid.get(BlockPos3i(1, 0, 0))!!.properties
        assertEquals("true", middlePane["east"])
        assertEquals("true", middlePane["west"])
        assertEquals("true", middlePane["north"])
        assertEquals("false", middlePane["south"])

        val explicitFence = grid.get(BlockPos3i(5, 0, 4))!!.properties
        assertEquals("true", explicitFence["north"])
        assertEquals("false", explicitFence["east"])
        assertEquals("true", explicitFence["south"])
        assertEquals("true", explicitFence["west"])
    }
}
