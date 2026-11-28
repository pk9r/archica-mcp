package com.archica.mcp.litematica

import com.archica.mcp.blueprint.BlockSpec
import com.archica.mcp.blueprint.BlueprintErrorCode
import com.archica.mcp.blueprint.BlueprintException
import com.archica.mcp.blueprint.BlueprintPhase
import com.archica.mcp.blueprint.VoxelGrid
import com.archica.mcp.context.BlockPos3i
import com.archica.mcp.context.CardinalDirection
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LitematicSchematicCompilerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `empty blueprint fails with INVALID_ARGUMENTS before writing any file`() {
        val compiler = LitematicSchematicCompiler()
        val schematicsDir = tempDir.resolve("schematics")
        val emptyGrid = VoxelGrid()

        val ex = assertFailsWith<BlueprintException> {
            compiler.compileToFile(
                blueprintId = "empty_bp",
                sessionOrigin = BlockPos3i(0, 64, 0),
                facing = CardinalDirection.NORTH,
                grid = emptyGrid,
                schematicsBaseDir = schematicsDir,
            )
        }
        assertEquals(BlueprintErrorCode.INVALID_ARGUMENTS, ex.code)
        assertFalse(Files.exists(schematicsDir.resolve("archica/empty_bp.litematic")))
    }

    @Test
    fun `rotates facing, normalizes negative overhang to worldBounds min, and encodes bit-packed v7 NBT`() {
        val compiler = LitematicSchematicCompiler(
            minecraftDataVersionProvider = { 4775 },
            clockMillis = { 1700000123456L },
        )
        val schematicsDir = tempDir.resolve("schematics")
        val grid = VoxelGrid()
        // Local overhang at x = -1, z = 0..2 and 5 distinct non-air states (6 total palette entries -> 3 bits/entry).
        grid.set(BlockPos3i(-1, 0, 0), BlockSpec("minecraft:cobblestone"))
        grid.set(BlockPos3i(0, 0, 0), BlockSpec("minecraft:oak_planks"))
        grid.set(BlockPos3i(1, 0, 0), BlockSpec("minecraft:stone_bricks"))
        grid.set(BlockPos3i(0, 1, 1), BlockSpec("minecraft:oak_stairs", mapOf("facing" to "north", "half" to "bottom")))
        grid.set(BlockPos3i(0, 2, 2), BlockSpec("minecraft:oak_log", mapOf("axis" to "z")))
        grid.set(BlockPos3i(1, 2, 2), BlockSpec("minecraft:glass"))

        val compiled = compiler.compileToFile(
            blueprintId = "east_house",
            sessionOrigin = BlockPos3i(100, 64, 100),
            facing = CardinalDirection.EAST,
            grid = grid,
            schematicsBaseDir = schematicsDir,
        )

        // EAST rotation: (lx, ly, lz) -> (lz, ly, lx)
        // (-1,0,0)->(100,64,99), (1,2,2)->(102,66,101) => min=[100,64,99], size=3x3x3
        assertEquals(BlockPos3i(100, 64, 99), compiled.worldOrigin)
        assertEquals(BlockPos3i(3, 3, 3), compiled.enclosingSize)
        assertEquals(6, compiled.totalBlocks)
        assertEquals("schematics/archica/east_house.litematic", compiled.schematicPath)
        assertTrue(Files.exists(compiled.filePath))

        val root = NbtBinaryCodec.fromGzipBytes(Files.readAllBytes(compiled.filePath))
        assertEquals(7, root.getInt("Version"))
        assertEquals(1, root.getInt("SubVersion"))
        assertEquals(4775, root.getInt("MinecraftDataVersion"))

        val metadata = root.getCompound("Metadata")!!
        assertEquals("east_house", metadata.getString("Name"))
        assertEquals("Archica MCP", metadata.getString("Author"))
        assertEquals(1, metadata.getInt("RegionCount"))
        assertEquals(27, metadata.getInt("TotalVolume"))
        assertEquals(6, metadata.getInt("TotalBlocks"))
        assertEquals(1700000123456L, metadata.getLong("TimeCreated"))

        val region = root.getCompound("Regions")!!.getCompound("east_house")!!
        assertEquals(0, region.getCompound("Position")!!.getInt("x"))
        assertEquals(0, region.getCompound("Position")!!.getInt("y"))
        assertEquals(0, region.getCompound("Position")!!.getInt("z"))
        assertEquals(3, region.getCompound("Size")!!.getInt("x"))
        assertEquals(3, region.getCompound("Size")!!.getInt("y"))
        assertEquals(3, region.getCompound("Size")!!.getInt("z"))

        val palette = region.getList("BlockStatePalette")!!.elements.map { it as NbtTag.CompoundTag }
        assertEquals("minecraft:air", palette.first().getString("Name"))
        assertEquals(7, palette.size) // air + 6 unique blocks -> 3 bits/entry

        val packed = region.getLongArray("BlockStates")!!
        val bitArray = LitematicaBitArray(LitematicaBitArray.bitsForPaletteSize(palette.size), 27L, packed)

        fun blockAt(nx: Int, ny: Int, nz: Int): Pair<String, Map<String, String>> {
            val idx = ny.toLong() * 9L + nz.toLong() * 3L + nx.toLong()
            val entry = palette[bitArray.getAt(idx)]
            val name = entry.getString("Name")!!
            val props = entry.getCompound("Properties")?.entries?.mapValues { (_, v) ->
                (v as NbtTag.StringTag).value
            } ?: emptyMap()
            return name to props
        }

        // (-1,0,0) -> (100,64,99) -> normalized (0,0,0)
        assertEquals("minecraft:cobblestone" to emptyMap(), blockAt(0, 0, 0))
        // (0,1,1) -> (101,65,100) -> normalized (1,1,1); local facing=north rotated EAST -> facing=east
        assertEquals(
            "minecraft:oak_stairs" to mapOf("facing" to "east", "half" to "bottom"),
            blockAt(1, 1, 1),
        )
        // (0,2,2) -> (102,66,100) -> normalized (2,2,1); local axis=z rotated EAST -> axis=x
        assertEquals("minecraft:oak_log" to mapOf("axis" to "x"), blockAt(2, 2, 1))
        // Empty cell -> air
        assertEquals("minecraft:air" to emptyMap(), blockAt(2, 0, 0))
    }

    @Test
    fun `originOverride shifts worldOrigin without mutating relative layout`() {
        val compiler = LitematicSchematicCompiler()
        val schematicsDir = tempDir.resolve("schematics")
        val grid = VoxelGrid()
        grid.set(BlockPos3i(0, 0, 0), BlockSpec("minecraft:stone"))
        grid.set(BlockPos3i(1, 0, 0), BlockSpec("minecraft:stone"))

        val compiled = compiler.compileToFile(
            blueprintId = "override_test",
            sessionOrigin = BlockPos3i(10, 64, 10),
            facing = CardinalDirection.NORTH,
            grid = grid,
            schematicsBaseDir = schematicsDir,
            originOverride = BlockPos3i(50, 80, -20),
        )

        assertEquals(BlockPos3i(50, 80, -20), compiled.worldOrigin)
        assertEquals(BlockPos3i(2, 1, 1), compiled.enclosingSize)
    }

    @Test
    fun `phased blueprint compiles multi-region litematic v7 with accurate relative subregion positions`() {
        val compiler = LitematicSchematicCompiler()
        val schematicsDir = tempDir.resolve("schematics")
        val grid = VoxelGrid()
        // Phase 1 (foundation): (0,0,0) and (2,0,2)
        grid.set(BlockPos3i(0, 0, 0), BlockSpec("minecraft:cobblestone"), phaseId = "foundation")
        grid.set(BlockPos3i(2, 0, 2), BlockSpec("minecraft:cobblestone"), phaseId = "foundation")
        // Phase 2 (frame): (1,1,1) and (1,2,1)
        grid.set(BlockPos3i(1, 1, 1), BlockSpec("minecraft:oak_log", mapOf("axis" to "y")), phaseId = "frame")
        grid.set(BlockPos3i(1, 2, 1), BlockSpec("minecraft:oak_log", mapOf("axis" to "y")), phaseId = "frame")
        // Phase 3 (roof): (0,3,0) to (2,3,2)
        grid.set(BlockPos3i(0, 3, 0), BlockSpec("minecraft:oak_planks"), phaseId = "roof")
        grid.set(BlockPos3i(2, 3, 2), BlockSpec("minecraft:oak_planks"), phaseId = "roof")

        val phases = listOf(
            BlueprintPhase("foundation", 1, "Foundation"),
            BlueprintPhase("frame", 2, "Frame"),
            BlueprintPhase("roof", 3, "Roof"),
        )
        val compiled = compiler.compileToFile(
            blueprintId = "phased_villa",
            sessionOrigin = BlockPos3i(10, 64, 10),
            facing = CardinalDirection.NORTH,
            grid = grid,
            schematicsBaseDir = schematicsDir,
            phases = phases,
        )

        assertEquals(6, compiled.totalBlocks)
        assertEquals(BlockPos3i(3, 4, 3), compiled.enclosingSize)

        val root = NbtBinaryCodec.fromGzipBytes(Files.readAllBytes(compiled.filePath))
        val metadata = root.getCompound("Metadata")!!
        assertEquals(3, metadata.getInt("RegionCount"))
        assertEquals(6, metadata.getInt("TotalBlocks"))
        assertEquals(36, metadata.getInt("TotalVolume"))

        val regions = root.getCompound("Regions")!!
        val r1 = regions.getCompound("01_foundation")!!
        assertEquals(0, r1.getCompound("Position")!!.getInt("x"))
        assertEquals(0, r1.getCompound("Position")!!.getInt("y"))
        assertEquals(0, r1.getCompound("Position")!!.getInt("z"))
        assertEquals(3, r1.getCompound("Size")!!.getInt("x"))
        assertEquals(1, r1.getCompound("Size")!!.getInt("y"))
        assertEquals(3, r1.getCompound("Size")!!.getInt("z"))

        val r2 = regions.getCompound("02_frame")!!
        assertEquals(1, r2.getCompound("Position")!!.getInt("x"))
        assertEquals(1, r2.getCompound("Position")!!.getInt("y"))
        assertEquals(1, r2.getCompound("Position")!!.getInt("z"))
        assertEquals(1, r2.getCompound("Size")!!.getInt("x"))
        assertEquals(2, r2.getCompound("Size")!!.getInt("y"))
        assertEquals(1, r2.getCompound("Size")!!.getInt("z"))

        val r3 = regions.getCompound("03_roof")!!
        assertEquals(0, r3.getCompound("Position")!!.getInt("x"))
        assertEquals(3, r3.getCompound("Position")!!.getInt("y"))
        assertEquals(0, r3.getCompound("Position")!!.getInt("z"))
        assertEquals(3, r3.getCompound("Size")!!.getInt("x"))
        assertEquals(1, r3.getCompound("Size")!!.getInt("y"))
        assertEquals(3, r3.getCompound("Size")!!.getInt("z"))
    }
}
