package com.archica.mcp.litematica

import com.archica.mcp.blueprint.BlockSpec
import com.archica.mcp.blueprint.BlueprintBounds
import com.archica.mcp.blueprint.BlueprintErrorCode
import com.archica.mcp.blueprint.BlueprintException
import com.archica.mcp.blueprint.BlueprintTransform
import com.archica.mcp.blueprint.VoxelGrid
import com.archica.mcp.context.BlockPos3i
import com.archica.mcp.context.CardinalDirection
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Result of compiling a rasterized [VoxelGrid] into a `.litematic` file on disk. */
data class CompiledLitematic(
    val filePath: Path,
    val schematicPath: String,
    val worldOrigin: BlockPos3i,
    val enclosingSize: BlockPos3i,
    val totalBlocks: Int,
)

/**
 * Compiles anchor-relative [VoxelGrid] blueprints into standard GZIP-compressed `.litematic` (v7, SubVersion 1) files.
 */
class LitematicSchematicCompiler(
    private val minecraftDataVersionProvider: () -> Int = { DEFAULT_MINECRAFT_DATA_VERSION },
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    fun compileToFile(
        blueprintId: String,
        sessionOrigin: BlockPos3i,
        facing: CardinalDirection,
        grid: VoxelGrid,
        schematicsBaseDir: Path,
        originOverride: BlockPos3i? = null,
    ): CompiledLitematic {
        if (grid.totalBlocks == 0) {
            throw BlueprintException(
                BlueprintErrorCode.INVALID_ARGUMENTS,
                "Cannot compile empty blueprint '$blueprintId' with 0 non-air blocks.",
            )
        }

        val effectiveOrigin = originOverride ?: sessionOrigin
        val worldEntries = LinkedHashMap<BlockPos3i, BlockSpec>(grid.totalBlocks)
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var maxZ = Int.MIN_VALUE

        for ((localPos, localSpec) in grid.entries()) {
            if (localSpec.isAir) continue
            val worldPos = BlueprintTransform.toWorldPos(localPos, effectiveOrigin, facing)
            val worldSpec = normalizeProperties(BlueprintTransform.transformBlockSpec(localSpec, facing))
            worldEntries[worldPos] = worldSpec

            if (worldPos.x < minX) minX = worldPos.x
            if (worldPos.y < minY) minY = worldPos.y
            if (worldPos.z < minZ) minZ = worldPos.z
            if (worldPos.x > maxX) maxX = worldPos.x
            if (worldPos.y > maxY) maxY = worldPos.y
            if (worldPos.z > maxZ) maxZ = worldPos.z
        }

        if (worldEntries.isEmpty()) {
            throw BlueprintException(
                BlueprintErrorCode.INVALID_ARGUMENTS,
                "Cannot compile empty blueprint '$blueprintId' with 0 non-air blocks.",
            )
        }

        val worldBounds = BlueprintBounds(
            min = BlockPos3i(minX, minY, minZ),
            max = BlockPos3i(maxX, maxY, maxZ),
        )
        grid.checkBoundsCap(worldBounds)

        val sizeX = worldBounds.sizeX
        val sizeY = worldBounds.sizeY
        val sizeZ = worldBounds.sizeZ
        val totalVolume = worldBounds.volumeLong()

        val palette = ArrayList<BlockSpec>()
        val paletteIds = LinkedHashMap<BlockSpec, Int>()
        palette.add(BlockSpec.AIR)
        paletteIds[BlockSpec.AIR] = 0

        for (spec in worldEntries.values) {
            if (!paletteIds.containsKey(spec)) {
                paletteIds[spec] = palette.size
                palette.add(spec)
            }
        }

        val bitsPerEntry = LitematicaBitArray.bitsForPaletteSize(palette.size)
        val bitArray = LitematicaBitArray(bitsPerEntry = bitsPerEntry, arraySize = totalVolume)
        val layerSize = sizeX.toLong() * sizeZ.toLong()
        val rowStride = sizeX.toLong()

        for ((worldPos, spec) in worldEntries) {
            val nx = (worldPos.x - minX).toLong()
            val ny = (worldPos.y - minY).toLong()
            val nz = (worldPos.z - minZ).toLong()
            val index = ny * layerSize + nz * rowStride + nx
            bitArray.setAt(index, paletteIds.getValue(spec))
        }

        val now = clockMillis()
        val root = buildSchematicNbt(
            blueprintId = blueprintId,
            facing = facing,
            sizeX = sizeX,
            sizeY = sizeY,
            sizeZ = sizeZ,
            totalVolume = totalVolume.toInt(),
            totalBlocks = worldEntries.size,
            timestampMillis = now,
            palette = palette,
            packedBlockStates = bitArray.backingLongArray,
        )

        val archicaDir = schematicsBaseDir.resolve(ARCHICA_SUBDIR)
        Files.createDirectories(archicaDir)
        val targetFile = archicaDir.resolve("$blueprintId$LITEMATIC_EXTENSION")
        writeAtomically(archicaDir, blueprintId, targetFile, root)

        val relativePath = formatSchematicPath(schematicsBaseDir, targetFile, blueprintId)
        return CompiledLitematic(
            filePath = targetFile,
            schematicPath = relativePath,
            worldOrigin = worldBounds.min,
            enclosingSize = BlockPos3i(sizeX, sizeY, sizeZ),
            totalBlocks = worldEntries.size,
        )
    }

    private fun buildSchematicNbt(
        blueprintId: String,
        facing: CardinalDirection,
        sizeX: Int,
        sizeY: Int,
        sizeZ: Int,
        totalVolume: Int,
        totalBlocks: Int,
        timestampMillis: Long,
        palette: List<BlockSpec>,
        packedBlockStates: LongArray,
    ): NbtTag.CompoundTag {
        val enclosingSize = vec3iTag(sizeX, sizeY, sizeZ)
        val metadata = NbtTag.CompoundTag()
            .putString("Name", blueprintId)
            .putString("Author", ARCHICA_AUTHOR)
            .putString("Description", "Compiled by Archica MCP (facing=${facing.name})")
            .putInt("RegionCount", 1)
            .putInt("TotalVolume", totalVolume)
            .putInt("TotalBlocks", totalBlocks)
            .putLong("TimeCreated", timestampMillis)
            .putLong("TimeModified", timestampMillis)
            .put("EnclosingSize", enclosingSize)

        val paletteList = NbtTag.ListTag(
            elementType = NbtTag.TAG_COMPOUND,
            elements = palette.map { spec ->
                NbtTag.CompoundTag().apply {
                    putString("Name", spec.blockId)
                    if (spec.properties.isNotEmpty()) {
                        val propsTag = NbtTag.CompoundTag()
                        for ((k, v) in spec.properties) {
                            propsTag.putString(k, v)
                        }
                        put("Properties", propsTag)
                    }
                }
            },
        )

        val regionTag = NbtTag.CompoundTag()
            .put("Position", vec3iTag(0, 0, 0))
            .put("Size", vec3iTag(sizeX, sizeY, sizeZ))
            .put("BlockStatePalette", paletteList)
            .putLongArray("BlockStates", packedBlockStates)
            .put("TileEntities", NbtTag.ListTag(NbtTag.TAG_COMPOUND, emptyList()))
            .put("Entities", NbtTag.ListTag(NbtTag.TAG_COMPOUND, emptyList()))

        val regions = NbtTag.CompoundTag().put(blueprintId, regionTag)

        return NbtTag.CompoundTag()
            .putInt("MinecraftDataVersion", minecraftDataVersionProvider())
            .putInt("Version", LITEMATIC_VERSION)
            .putInt("SubVersion", LITEMATIC_SUB_VERSION)
            .put("Metadata", metadata)
            .put("Regions", regions)
    }

    private fun writeAtomically(archicaDir: Path, blueprintId: String, targetFile: Path, root: NbtTag.CompoundTag) {
        val tempFile = Files.createTempFile(archicaDir, "$blueprintId-", "$LITEMATIC_EXTENSION.tmp")
        try {
            Files.newOutputStream(tempFile).use { out ->
                NbtBinaryCodec.writeGzipCompound(root, out)
            }
            try {
                Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(tempFile) }
            throw t
        }
    }

    private fun formatSchematicPath(schematicsBaseDir: Path, targetFile: Path, blueprintId: String): String {
        val dirName = schematicsBaseDir.fileName?.toString()
        return if (dirName != null && dirName.equals("schematics", ignoreCase = true)) {
            "schematics/$ARCHICA_SUBDIR/$blueprintId$LITEMATIC_EXTENSION"
        } else {
            targetFile.toString().replace('\\', '/')
        }
    }

    private fun normalizeProperties(spec: BlockSpec): BlockSpec =
        if (spec.properties.size <= 1) spec else spec.copy(properties = spec.properties.toSortedMap())

    private fun vec3iTag(x: Int, y: Int, z: Int): NbtTag.CompoundTag = NbtTag.CompoundTag()
        .putInt("x", x)
        .putInt("y", y)
        .putInt("z", z)

    companion object {
        const val LITEMATIC_VERSION = 7
        const val LITEMATIC_SUB_VERSION = 1
        const val DEFAULT_MINECRAFT_DATA_VERSION = 4775
        const val ARCHICA_AUTHOR = "Archica MCP"
        const val ARCHICA_SUBDIR = "archica"
        const val LITEMATIC_EXTENSION = ".litematic"
    }
}
