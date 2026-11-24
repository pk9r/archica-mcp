package com.archica.mcp.context

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Input parameters for [BuildContextScanner]. */
data class BuildContextRequest(
    val radius: Int = BuildContextScanner.DEFAULT_RADIUS,
)

/** Surface sample resolved for a single `(x, z)` column. */
data class ColumnSurface(
    val y: Int,
    val sample: BlockSample,
)

/** Suggested outdoor build origin calculated ahead of the player's gaze. */
data class SuggestedOrigin(
    val pos: BlockPos3i,
    val groundY: Int?,
    val surfaceBlockId: String,
)

/** Structured result of `get_build_context`, including compact text serialization. */
data class BuildContextResult(
    val player: PlayerSnapshot,
    val suggestedOrigin: SuggestedOrigin,
    val inventoryMaterials: List<InventoryEntry>,
    val legend: SurfaceLegend,
    val originX: Int,
    val originZ: Int,
    val radius: Int,
    val rows: List<TerrainGridRow>,
) {
    val gridSize: Int
        get() = 2 * radius + 1

    fun formatText(): String = buildString {
        appendLine("[PLAYER]")
        append("pos=").append(player.pos.formatTuple())
        append(" facing=").append(player.facing.name)
        append("(yaw=").append(player.yawInt()).append(", pitch=").append(player.pitchInt()).append(')')
        append(" biome=").append(player.biome)
        append(" dim=").append(player.dimension)
        append(" mode=").append(player.gameMode)
        appendLine()

        append("suggested_origin=").append(suggestedOrigin.pos.formatTuple())
        append(" ground_y=").append(suggestedOrigin.groundY?.toString() ?: "none")
        append(" surface_block=").append(suggestedOrigin.surfaceBlockId)
        appendLine()
        appendLine()

        appendLine("[INVENTORY_MATERIALS]")
        if (inventoryMaterials.isEmpty()) {
            appendLine("none")
        } else {
            appendLine(inventoryMaterials.joinToString(", ") { "${formatItemName(it.itemId)}:${it.count}" })
        }
        appendLine()

        appendLine("[SURFACE_LEGEND]")
        appendLine(legend.formatLine())
        appendLine()

        appendLine("[TERRAIN_GRID origin=($originX, $originZ) size=${gridSize}x${gridSize} ref_y=${player.pos.y}]")
        val rowLabels = rows.map { "z${it.z}:" }
        val maxPrefixLen = rowLabels.maxOfOrNull { it.length } ?: 0
        val maxCellLen = rows.flatMap { it.cells }.maxOfOrNull { it.length }?.coerceAtLeast(2) ?: 2

        rows.forEachIndexed { index, row ->
            append(rowLabels[index].padEnd(maxPrefixLen))
            append(' ')
            append(row.cells.joinToString(" ") { it.padEnd(maxCellLen) }.trimEnd())
            if (index < rows.lastIndex) appendLine()
        }
    }

    private fun formatItemName(itemId: String): String =
        itemId.removePrefix("minecraft:")
}

/** Single Z-row of formatted `<symbol><deltaY>` tokens across ascending X. */
data class TerrainGridRow(
    val z: Int,
    val cells: List<String>,
)

/** Pure Kotlin scanner that builds compact outdoor context summaries from a [WorldContextSource]. */
class BuildContextScanner(
    private val source: WorldContextSource,
) {
    fun scan(request: BuildContextRequest = BuildContextRequest()): BuildContextResult {
        if (!source.isWorldLoaded()) {
            throw ContextCompressionException(
                ContextErrorCode.NO_WORLD_LOADED,
                "Minecraft client is not currently in a world (title screen or disconnecting).",
            )
        }
        if (request.radius !in MIN_RADIUS..MAX_RADIUS) {
            throw ContextCompressionException(
                ContextErrorCode.INVALID_RADIUS,
                "Radius ${request.radius} is out of bounds; expected $MIN_RADIUS..$MAX_RADIUS.",
            )
        }

        val player = source.getPlayerSnapshot()
        val worldBottom = source.worldMinY()
        val worldTop = source.worldMaxY()
        val scanMinY = max(worldBottom, player.pos.y - VERTICAL_DOWN)
        val scanMaxY = min(worldTop, player.pos.y + VERTICAL_UP)

        val radius = request.radius
        val minX = player.pos.x - radius
        val maxX = player.pos.x + radius
        val minZ = player.pos.z - radius
        val maxZ = player.pos.z + radius

        val columnCache = HashMap<Pair<Int, Int>, ColumnSurface?>((2 * radius + 1) * (2 * radius + 1))
        val encounteredBlocks = ArrayList<String>()

        for (z in minZ..maxZ) {
            for (x in minX..maxX) {
                val surface = scanColumn(x, z, scanMinY, scanMaxY)
                columnCache[x to z] = surface
                if (surface != null) {
                    encounteredBlocks.add(surface.sample.blockId)
                }
            }
        }

        val legend = SurfaceLegend.assign(encounteredBlocks)
        val rows = (minZ..maxZ).map { z ->
            val cells = (minX..maxX).map { x ->
                formatCell(columnCache[x to z], player.pos.y, legend)
            }
            TerrainGridRow(z = z, cells = cells)
        }

        val suggestedOrigin = resolveSuggestedOrigin(
            player = player,
            scanMinY = scanMinY,
            scanMaxY = scanMaxY,
            columnCache = columnCache,
        )

        val materials = aggregateInventory(source.getInventoryMaterials())

        return BuildContextResult(
            player = player,
            suggestedOrigin = suggestedOrigin,
            inventoryMaterials = materials,
            legend = legend,
            originX = minX,
            originZ = minZ,
            radius = radius,
            rows = rows,
        )
    }

    private fun scanColumn(x: Int, z: Int, scanMinY: Int, scanMaxY: Int): ColumnSurface? {
        for (y in scanMaxY downTo scanMinY) {
            val sample = source.getBlock(x, y, z)
            if (!sample.shouldSkipInSurfaceScan()) {
                return ColumnSurface(y = y, sample = sample)
            }
        }
        return null
    }

    private fun formatCell(surface: ColumnSurface?, refY: Int, legend: SurfaceLegend): String {
        if (surface == null) return OUT_OF_WINDOW_TOKEN
        val symbol = legend.symbolFor(surface.sample.blockId)
        val delta = surface.y - refY
        val deltaStr = when {
            delta > 0 -> "+$delta"
            else -> delta.toString()
        }
        return "$symbol$deltaStr"
    }

    private fun resolveSuggestedOrigin(
        player: PlayerSnapshot,
        scanMinY: Int,
        scanMaxY: Int,
        columnCache: MutableMap<Pair<Int, Int>, ColumnSurface?>,
    ): SuggestedOrigin {
        val rad = Math.toRadians(player.yaw.toDouble())
        val dx = -sin(rad)
        val dz = cos(rad)
        val targetX = player.pos.x + (dx * TARGET_LOOK_DISTANCE).roundToInt()
        val targetZ = player.pos.z + (dz * TARGET_LOOK_DISTANCE).roundToInt()

        fun surfaceAt(x: Int, z: Int): ColumnSurface? =
            columnCache.getOrPut(x to z) { scanColumn(x, z, scanMinY, scanMaxY) }

        val targetSurface = surfaceAt(targetX, targetZ)
        if (targetSurface != null && targetSurface.sample.isSolidGroundForOrigin()) {
            return SuggestedOrigin(
                pos = BlockPos3i(targetX, targetSurface.y + 1, targetZ),
                groundY = targetSurface.y,
                surfaceBlockId = targetSurface.sample.blockId,
            )
        }

        // Fallback search within Chebyshev radius <= 3 ordered by Euclidean distance squared.
        val offsets = buildList {
            for (oz in -ORIGIN_FALLBACK_RADIUS..ORIGIN_FALLBACK_RADIUS) {
                for (ox in -ORIGIN_FALLBACK_RADIUS..ORIGIN_FALLBACK_RADIUS) {
                    if (ox == 0 && oz == 0) continue
                    add(ox to oz)
                }
            }
        }.sortedWith(compareBy<Pair<Int, Int>>({ it.first * it.first + it.second * it.second }, { it.second }, { it.first }))

        for ((ox, oz) in offsets) {
            val candidateX = targetX + ox
            val candidateZ = targetZ + oz
            val candidate = surfaceAt(candidateX, candidateZ)
            if (candidate != null && candidate.sample.isSolidGroundForOrigin()) {
                return SuggestedOrigin(
                    pos = BlockPos3i(candidateX, candidate.y + 1, candidateZ),
                    groundY = candidate.y,
                    surfaceBlockId = candidate.sample.blockId,
                )
            }
        }

        return if (targetSurface != null) {
            SuggestedOrigin(
                pos = BlockPos3i(targetX, targetSurface.y + 1, targetZ),
                groundY = targetSurface.y,
                surfaceBlockId = targetSurface.sample.blockId,
            )
        } else {
            SuggestedOrigin(
                pos = BlockPos3i(targetX, player.pos.y, targetZ),
                groundY = null,
                surfaceBlockId = BlockSample.AIR_ID,
            )
        }
    }

    private fun aggregateInventory(entries: List<InventoryEntry>): List<InventoryEntry> {
        if (entries.isEmpty()) return emptyList()
        val totals = LinkedHashMap<String, Int>()
        for (entry in entries) {
            if (entry.count <= 0 || entry.itemId == BlockSample.AIR_ID) continue
            totals[entry.itemId] = (totals[entry.itemId] ?: 0) + entry.count
        }
        return totals.entries
            .map { InventoryEntry(itemId = it.key, count = it.value) }
            .sortedWith(compareByDescending<InventoryEntry> { it.count }.thenBy { it.itemId })
    }

    companion object {
        const val DEFAULT_RADIUS = 12
        const val MIN_RADIUS = 4
        const val MAX_RADIUS = 24
        const val VERTICAL_DOWN = 16
        const val VERTICAL_UP = 24
        const val TARGET_LOOK_DISTANCE = 10.0
        const val ORIGIN_FALLBACK_RADIUS = 3
        const val OUT_OF_WINDOW_TOKEN = "?"
    }
}
