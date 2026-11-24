package com.archica.mcp.context

/** Input parameters for [VolumeScanner]. */
data class ScanVolumeRequest(
    val min: BlockPos3i,
    val max: BlockPos3i,
    val includeStates: Boolean = false,
)

/** Single run-length encoded segment across X: `count*paletteId`. */
data class RleRun(
    val count: Int,
    val paletteId: Int,
) {
    override fun toString(): String = "$count*$paletteId"
}

/** Single Z-row inside a Y-slice, RLE-compressed across ascending X. */
data class VolumeRow(
    val z: Int,
    val runs: List<RleRun>,
)

/** Non-empty horizontal Y-slice inside a scanned volume. */
data class VolumeLayer(
    val y: Int,
    val rows: List<VolumeRow>,
)

/** Structured result of `scan_volume`, including compact RLE text serialization. */
data class ScanVolumeResult(
    val min: BlockPos3i,
    val max: BlockPos3i,
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    val palette: List<String>,
    val layers: List<VolumeLayer>,
) {
    fun formatText(): String = buildString {
        appendLine("[VOLUME min=${min.formatTuple()} max=${max.formatTuple()} size=${sizeX}x${sizeY}x${sizeZ}]")
        appendLine("[PALETTE]")
        append(palette.mapIndexed { index, entry -> "$index=$entry" }.joinToString("  "))

        if (layers.isNotEmpty()) {
            appendLine()
            for (layer in layers) {
                appendLine()
                appendLine("y=${layer.y}:")
                layer.rows.forEachIndexed { index, row ->
                    append("  z${row.z}: ")
                    append(row.runs.joinToString(", "))
                    if (index < layer.rows.lastIndex) appendLine()
                }
            }
        }
    }
}

/** Normalizes block IDs and optional geometric blockstate properties for `[PALETTE]` entries. */
object BlockStateNormalizer {
    private val alwaysStripProperties = setOf(
        "distance",
        "persistent",
        "stage",
        "age",
    )

    private val stripWhenFalseProperties = setOf(
        "waterlogged",
        "powered",
        "lit",
        "snowy",
        "in_wall",
        "attached",
        "occupied",
        "triggered",
        "has_record",
        "has_book",
    )

    fun formatPaletteKey(sample: BlockSample, includeStates: Boolean): String {
        if (sample.isAir) return BlockSample.AIR_ID
        if (!includeStates || sample.properties.isEmpty()) return sample.blockId

        val filtered = sample.properties.entries
            .filter { (key, value) ->
                key !in alwaysStripProperties &&
                    !(key in stripWhenFalseProperties && value == "false")
            }
            .sortedBy { it.key }

        if (filtered.isEmpty()) return sample.blockId
        val serializedProps = filtered.joinToString(",") { (key, value) -> "$key=$value" }
        return "${sample.blockId}[$serializedProps]"
    }
}

/** Pure Kotlin scanner that compresses a bounded 3D box into non-empty Y-slices with X-axis RLE. */
class VolumeScanner(
    private val source: WorldContextSource,
) {
    fun scan(request: ScanVolumeRequest): ScanVolumeResult {
        if (!source.isWorldLoaded()) {
            throw ContextCompressionException(
                ContextErrorCode.NO_WORLD_LOADED,
                "Minecraft client is not currently in a world (title screen or disconnecting).",
            )
        }

        val min = request.min
        val max = request.max
        if (min.x > max.x || min.y > max.y || min.z > max.z) {
            throw ContextCompressionException(
                ContextErrorCode.INVALID_BOUNDS,
                "Invalid bounding box: min ${min.formatTuple()} must be <= max ${max.formatTuple()} on every axis.",
            )
        }

        val sizeXLong = max.x.toLong() - min.x.toLong() + 1L
        val sizeYLong = max.y.toLong() - min.y.toLong() + 1L
        val sizeZLong = max.z.toLong() - min.z.toLong() + 1L
        val totalBlocks = safeProduct(sizeXLong, sizeYLong, sizeZLong)
        if (totalBlocks == null || totalBlocks > MAX_VOLUME) {
            throw ContextCompressionException(
                ContextErrorCode.VOLUME_TOO_LARGE,
                "Requested volume (${totalBlocks ?: "overflow"} blocks) exceeds hard cap of $MAX_VOLUME blocks.",
            )
        }

        val sizeX = sizeXLong.toInt()
        val sizeY = sizeYLong.toInt()
        val sizeZ = sizeZLong.toInt()

        val palette = ArrayList<String>()
        val paletteIndices = HashMap<String, Int>()
        palette.add(BlockSample.AIR_ID)
        paletteIndices[BlockSample.AIR_ID] = 0

        val layers = ArrayList<VolumeLayer>()
        for (y in min.y..max.y) {
            val layerRows = ArrayList<VolumeRow>(sizeZ)
            var hasNonAir = false

            for (z in min.z..max.z) {
                val runs = ArrayList<RleRun>()
                var currentId = -1
                var currentCount = 0

                for (x in min.x..max.x) {
                    val sample = source.getBlock(x, y, z)
                    val key = BlockStateNormalizer.formatPaletteKey(sample, request.includeStates)
                    val paletteId = paletteIndices.getOrPut(key) {
                        palette.add(key)
                        palette.lastIndex
                    }
                    if (paletteId != 0) hasNonAir = true

                    if (paletteId == currentId) {
                        currentCount++
                    } else {
                        if (currentCount > 0) {
                            runs.add(RleRun(currentCount, currentId))
                        }
                        currentId = paletteId
                        currentCount = 1
                    }
                }

                if (currentCount > 0) {
                    runs.add(RleRun(currentCount, currentId))
                }
                layerRows.add(VolumeRow(z = z, runs = runs))
            }

            if (hasNonAir) {
                layers.add(VolumeLayer(y = y, rows = layerRows))
            }
        }

        return ScanVolumeResult(
            min = min,
            max = max,
            sizeX = sizeX,
            sizeY = sizeY,
            sizeZ = sizeZ,
            palette = palette,
            layers = layers,
        )
    }

    private fun safeProduct(a: Long, b: Long, c: Long): Long? {
        val ab = try {
            Math.multiplyExact(a, b)
        } catch (_: ArithmeticException) {
            return null
        }
        return try {
            Math.multiplyExact(ab, c)
        } catch (_: ArithmeticException) {
            null
        }
    }

    companion object {
        const val MAX_VOLUME = 13_824
    }
}
