package com.archica.mcp.blueprint

import com.archica.mcp.context.BlockPos3i

/**
 * In-memory sparse 3D voxel buffer storing anchor-relative local blocks with enclosing bounds and volume guards.
 */
class VoxelGrid(
    private val maxVolume: Long = MAX_RASTERIZED_VOLUME,
) {
    private val cells = LinkedHashMap<BlockPos3i, BlockSpec>()
    private val cellPhases = LinkedHashMap<BlockPos3i, String>()

    val totalBlocks: Int
        get() = cells.size

    fun get(pos: BlockPos3i): BlockSpec? = cells[pos]

    fun entries(): Map<BlockPos3i, BlockSpec> = cells

    fun phaseOf(pos: BlockPos3i): String? = cellPhases[pos]

    fun phaseEntries(): Map<BlockPos3i, String> = cellPhases

    fun set(pos: BlockPos3i, spec: BlockSpec, phaseId: String? = null) {
        if (spec.isAir) {
            cells.remove(pos)
            cellPhases.remove(pos)
            return
        }
        cells[pos] = spec
        if (phaseId != null) {
            cellPhases[pos] = phaseId
        } else {
            cellPhases.remove(pos)
        }
    }

    /** Updates [pos] to [spec] while preserving any existing phase attribution on that voxel. */
    fun replacePreservingPhase(pos: BlockPos3i, spec: BlockSpec) {
        if (spec.isAir) {
            cells.remove(pos)
            cellPhases.remove(pos)
            return
        }
        cells[pos] = spec
    }

    fun remove(pos: BlockPos3i) {
        cells.remove(pos)
        cellPhases.remove(pos)
    }

    /** Throws [BlueprintException] with [BlueprintErrorCode.VOLUME_TOO_LARGE] if [bounds] exceeds [maxVolume]. */
    fun checkBoundsCap(bounds: BlueprintBounds) {
        if (bounds.volumeLong() > maxVolume) {
            throw BlueprintException(
                BlueprintErrorCode.VOLUME_TOO_LARGE,
                "Blueprint enclosing volume ${bounds.sizeX}x${bounds.sizeY}x${bounds.sizeZ} " +
                    "(${bounds.volumeLong()} blocks) exceeds cap of $maxVolume blocks.",
            )
        }
    }

    /** Validates that all currently placed non-air voxels fit inside [maxVolume]. */
    fun checkEnclosingVolumeCap() {
        val bounds = enclosingBounds() ?: return
        checkBoundsCap(bounds)
    }

    /** Returns the minimal inclusive bounding box of all non-air voxels, or null if empty. */
    fun enclosingBounds(): BlueprintBounds? {
        if (cells.isEmpty()) return null
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var maxZ = Int.MIN_VALUE
        for (pos in cells.keys) {
            if (pos.x < minX) minX = pos.x
            if (pos.y < minY) minY = pos.y
            if (pos.z < minZ) minZ = pos.z
            if (pos.x > maxX) maxX = pos.x
            if (pos.y > maxY) maxY = pos.y
            if (pos.z > maxZ) maxZ = pos.z
        }
        return BlueprintBounds(
            min = BlockPos3i(minX, minY, minZ),
            max = BlockPos3i(maxX, maxY, maxZ),
        )
    }

    /**
     * Aggregates required survival materials by block ID, counting two-half doors at `(x, y, z)` + `(x, y+1, z)`
     * as one door item, sorted by descending count then block ID.
     */
    fun materialCounts(): Map<String, Int> = computeMaterialCounts(cells)

    /** Aggregates non-air voxels belonging to [phaseId] (or unattributed voxels when [phaseId] is null). */
    fun voxelsForPhase(phaseId: String?): Map<BlockPos3i, BlockSpec> {
        if (cells.isEmpty()) return emptyMap()
        val subset = LinkedHashMap<BlockPos3i, BlockSpec>()
        for ((pos, spec) in cells) {
            if (cellPhases[pos] == phaseId) {
                subset[pos] = spec
            }
        }
        return subset
    }

    /** Aggregates survival material counts scoped to voxels attributed to [phaseId]. */
    fun materialCountsForPhase(phaseId: String?): Map<String, Int> =
        computeMaterialCounts(voxelsForPhase(phaseId))

    /** Counts surviving non-air voxels attributed to [phaseId]. */
    fun blockCountForPhase(phaseId: String?): Int {
        var count = 0
        for (pos in cells.keys) {
            if (cellPhases[pos] == phaseId) {
                count++
            }
        }
        return count
    }

    /** Assigns [phaseId] to any surviving non-air voxel that currently has no phase attribution. */
    fun assignUnattributedPhase(phaseId: String) {
        for (pos in cells.keys) {
            if (!cellPhases.containsKey(pos)) {
                cellPhases[pos] = phaseId
            }
        }
    }

    companion object {
        const val MAX_RASTERIZED_VOLUME: Long = 131_072L

        fun computeMaterialCounts(voxels: Map<BlockPos3i, BlockSpec>): Map<String, Int> {
            if (voxels.isEmpty()) return emptyMap()
            val counts = LinkedHashMap<String, Int>()
            for ((pos, spec) in voxels) {
                if (spec.properties["half"] == "upper") {
                    val below = voxels[BlockPos3i(pos.x, pos.y - 1, pos.z)]
                    if (below != null && below.blockId == spec.blockId && below.properties["half"] == "lower") {
                        continue
                    }
                }
                counts[spec.blockId] = (counts[spec.blockId] ?: 0) + 1
            }
            return counts.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .associate { it.key to it.value }
        }
    }
}
