package com.archica.mcp.blueprint

import com.archica.mcp.context.BlockPos3i
import com.archica.mcp.context.CardinalDirection
import com.archica.mcp.context.InventoryEntry
import com.archica.mcp.context.WorldContextSource
import java.util.concurrent.ConcurrentHashMap

/** Snapshot of player game mode and inventory used for non-blocking survival material warnings. */
data class SurvivalInventorySnapshot(
    val gameMode: String,
    val inventory: List<InventoryEntry>,
)

/** Optional suspend provider returning player game mode & inventory, or null if no world is loaded. */
fun interface SurvivalSnapshotProvider {
    suspend fun getSnapshotOrNull(): SurvivalInventorySnapshot?

    companion object {
        fun fromWorldSource(source: WorldContextSource): SurvivalSnapshotProvider = SurvivalSnapshotProvider {
            if (!source.isWorldLoaded()) {
                null
            } else {
                val player = source.getPlayerSnapshot()
                SurvivalInventorySnapshot(
                    gameMode = player.gameMode,
                    inventory = source.getInventoryMaterials(),
                )
            }
        }
    }
}

/** Survival material check summary included on blueprint responses when in survival mode. */
data class SurvivalMaterialCheck(
    val gameMode: String,
    val missingMaterials: Map<String, Int>,
)

data class BlueprintCreateRequest(
    val blueprintId: String,
    val origin: BlockPos3i,
    val facing: CardinalDirection = CardinalDirection.NORTH,
    val overwrite: Boolean = false,
)

data class BlueprintCreateResult(
    val blueprintId: String,
    val origin: BlockPos3i,
    val facing: CardinalDirection,
    val opCount: Int,
)

data class BlueprintMutationResult(
    val blueprintId: String,
    val addedOpIds: List<String> = emptyList(),
    val updatedOpId: String? = null,
    val removedOpIds: List<String> = emptyList(),
    val totalOps: Int,
    val boundingBox: BlueprintBounds?,
    val totalBlocks: Int,
    val materials: Map<String, Int>,
    val survivalCheck: SurvivalMaterialCheck? = null,
)

data class BlueprintInspectResult(
    val blueprintId: String,
    val origin: BlockPos3i,
    val facing: CardinalDirection,
    val totalOps: Int,
    val ops: List<StoredBlueprintOp>? = null,
    val localBoundingBox: BlueprintBounds?,
    val worldBoundingBox: BlueprintBounds?,
    val totalBlocks: Int,
    val materials: Map<String, Int>,
    val survivalCheck: SurvivalMaterialCheck? = null,
)

data class BlueprintListItem(
    val blueprintId: String,
    val origin: BlockPos3i,
    val facing: CardinalDirection,
    val opCount: Int,
    val hasActivePlacement: Boolean,
)

data class BlueprintListResult(
    val blueprints: List<BlueprintListItem>,
)

/**
 * Thread-safe in-memory store for stateful blueprint draft sessions with monotonic `op_id` allocation.
 */
class BlueprintSessionStore(
    catalog: BlockCatalog,
    private val activePlacementChecker: (String) -> Boolean = { false },
    maxVolume: Long = VoxelGrid.MAX_RASTERIZED_VOLUME,
) {
    private val rasterizer = BlueprintRasterizer(catalog = catalog, maxVolume = maxVolume)
    private val sessions = ConcurrentHashMap<String, MutableSession>()

    private class MutableSession(
        val blueprintId: String,
        val origin: BlockPos3i,
        val facing: CardinalDirection,
        val ops: MutableList<StoredBlueprintOp> = mutableListOf(),
        var nextOpNumber: Int = 1,
    )

    fun create(request: BlueprintCreateRequest): BlueprintCreateResult {
        validateBlueprintId(request.blueprintId)
        val created = MutableSession(
            blueprintId = request.blueprintId,
            origin = request.origin,
            facing = request.facing,
        )
        if (request.overwrite) {
            sessions[request.blueprintId] = created
        } else {
            val previous = sessions.putIfAbsent(request.blueprintId, created)
            if (previous != null) {
                throw BlueprintException(
                    BlueprintErrorCode.BLUEPRINT_EXISTS,
                    "Blueprint '${request.blueprintId}' already exists (set overwrite=true to replace it).",
                )
            }
        }
        return BlueprintCreateResult(
            blueprintId = request.blueprintId,
            origin = request.origin,
            facing = request.facing,
            opCount = 0,
        )
    }

    fun addOps(blueprintId: String, ops: List<PrimitiveOp>): SessionEvaluation {
        if (ops.isEmpty()) {
            throw BlueprintException(BlueprintErrorCode.INVALID_ARGUMENTS, "Parameter 'ops' must contain at least one operation.")
        }
        ops.forEach { rasterizer.validate(it) }

        val session = requireSession(blueprintId)
        return synchronized(session) {
            val candidateOps = session.ops.map { it.op } + ops
            val grid = rasterizer.rasterize(candidateOps)
            val addedIds = ArrayList<String>(ops.size)
            for (op in ops) {
                val opId = "op_${session.nextOpNumber++}"
                session.ops.add(StoredBlueprintOp(opId = opId, op = op))
                addedIds.add(opId)
            }
            SessionEvaluation(
                blueprintId = session.blueprintId,
                origin = session.origin,
                facing = session.facing,
                addedOpIds = addedIds,
                ops = session.ops.toList(),
                grid = grid,
            )
        }
    }

    fun updateOp(blueprintId: String, opId: String, op: PrimitiveOp): SessionEvaluation {
        rasterizer.validate(op)
        val session = requireSession(blueprintId)
        return synchronized(session) {
            val index = session.ops.indexOfFirst { it.opId == opId }
            if (index < 0) {
                throw BlueprintException(
                    BlueprintErrorCode.OP_NOT_FOUND,
                    "Operation '$opId' was not found in blueprint '$blueprintId'.",
                )
            }
            val candidateOps = session.ops.mapIndexed { i, stored -> if (i == index) op else stored.op }
            val grid = rasterizer.rasterize(candidateOps)
            session.ops[index] = StoredBlueprintOp(opId = opId, op = op)
            SessionEvaluation(
                blueprintId = session.blueprintId,
                origin = session.origin,
                facing = session.facing,
                updatedOpId = opId,
                ops = session.ops.toList(),
                grid = grid,
            )
        }
    }

    fun removeOp(blueprintId: String, opId: String): SessionEvaluation {
        val session = requireSession(blueprintId)
        return synchronized(session) {
            val index = session.ops.indexOfFirst { it.opId == opId }
            if (index < 0) {
                throw BlueprintException(
                    BlueprintErrorCode.OP_NOT_FOUND,
                    "Operation '$opId' was not found in blueprint '$blueprintId'.",
                )
            }
            val candidateOps = session.ops.filterIndexed { i, _ -> i != index }.map { it.op }
            val grid = rasterizer.rasterize(candidateOps)
            session.ops.removeAt(index)
            SessionEvaluation(
                blueprintId = session.blueprintId,
                origin = session.origin,
                facing = session.facing,
                removedOpIds = listOf(opId),
                ops = session.ops.toList(),
                grid = grid,
            )
        }
    }

    fun undo(blueprintId: String, count: Int = 1): SessionEvaluation {
        if (count < 1) {
            throw BlueprintException(BlueprintErrorCode.INVALID_ARGUMENTS, "Parameter 'count' must be >= 1.")
        }
        val session = requireSession(blueprintId)
        return synchronized(session) {
            val removeCount = minOf(count, session.ops.size)
            val removedIds = if (removeCount == 0) {
                emptyList()
            } else {
                session.ops.subList(session.ops.size - removeCount, session.ops.size).map { it.opId }
            }
            val remaining = session.ops.dropLast(removeCount)
            val grid = rasterizer.rasterize(remaining.map { it.op })
            repeat(removeCount) { session.ops.removeAt(session.ops.lastIndex) }
            SessionEvaluation(
                blueprintId = session.blueprintId,
                origin = session.origin,
                facing = session.facing,
                removedOpIds = removedIds,
                ops = session.ops.toList(),
                grid = grid,
            )
        }
    }

    fun inspect(blueprintId: String): SessionEvaluation {
        val session = requireSession(blueprintId)
        return synchronized(session) {
            val snapshotOps = session.ops.toList()
            val grid = rasterizer.rasterize(snapshotOps.map { it.op })
            SessionEvaluation(
                blueprintId = session.blueprintId,
                origin = session.origin,
                facing = session.facing,
                ops = snapshotOps,
                grid = grid,
            )
        }
    }

    fun list(): BlueprintListResult {
        val items = sessions.values.map { session ->
            synchronized(session) {
                BlueprintListItem(
                    blueprintId = session.blueprintId,
                    origin = session.origin,
                    facing = session.facing,
                    opCount = session.ops.size,
                    hasActivePlacement = activePlacementChecker(session.blueprintId),
                )
            }
        }.sortedBy { it.blueprintId }
        return BlueprintListResult(items)
    }

    private fun requireSession(blueprintId: String): MutableSession =
        sessions[blueprintId] ?: throw BlueprintException(
            BlueprintErrorCode.BLUEPRINT_NOT_FOUND,
            "Blueprint '$blueprintId' does not exist.",
        )

    private fun validateBlueprintId(blueprintId: String) {
        if (!BLUEPRINT_ID_REGEX.matches(blueprintId)) {
            throw BlueprintException(
                BlueprintErrorCode.INVALID_ARGUMENTS,
                "Invalid blueprint_id '$blueprintId': must match ^[a-z0-9_-]{1,48}$.",
            )
        }
    }

    data class SessionEvaluation(
        val blueprintId: String,
        val origin: BlockPos3i,
        val facing: CardinalDirection,
        val addedOpIds: List<String> = emptyList(),
        val updatedOpId: String? = null,
        val removedOpIds: List<String> = emptyList(),
        val ops: List<StoredBlueprintOp>,
        val grid: VoxelGrid,
    )

    companion object {
        private val BLUEPRINT_ID_REGEX = Regex("^[a-z0-9_-]{1,48}$")
    }
}

/**
 * Coordinates [BlueprintSessionStore] mutations/inspections with optional non-blocking survival material checks.
 */
class BlueprintService(
    catalog: BlockCatalog,
    private val survivalProvider: SurvivalSnapshotProvider = SurvivalSnapshotProvider { null },
    activePlacementChecker: (String) -> Boolean = { false },
    maxVolume: Long = VoxelGrid.MAX_RASTERIZED_VOLUME,
) {
    private val store = BlueprintSessionStore(
        catalog = catalog,
        activePlacementChecker = activePlacementChecker,
        maxVolume = maxVolume,
    )

    fun create(request: BlueprintCreateRequest): BlueprintCreateResult = store.create(request)

    suspend fun addOps(blueprintId: String, ops: List<PrimitiveOp>): BlueprintMutationResult =
        toMutationResult(store.addOps(blueprintId, ops))

    suspend fun updateOp(blueprintId: String, opId: String, op: PrimitiveOp): BlueprintMutationResult =
        toMutationResult(store.updateOp(blueprintId, opId, op))

    suspend fun removeOp(blueprintId: String, opId: String): BlueprintMutationResult =
        toMutationResult(store.removeOp(blueprintId, opId))

    suspend fun undo(blueprintId: String, count: Int = 1): BlueprintMutationResult =
        toMutationResult(store.undo(blueprintId, count))

    suspend fun inspect(blueprintId: String, includeOps: Boolean = true): BlueprintInspectResult {
        val eval = store.inspect(blueprintId)
        val localBounds = eval.grid.enclosingBounds()
        val worldBounds = localBounds?.let { BlueprintTransform.toWorldBounds(it, eval.origin, eval.facing) }
        val materials = eval.grid.materialCounts()
        return BlueprintInspectResult(
            blueprintId = eval.blueprintId,
            origin = eval.origin,
            facing = eval.facing,
            totalOps = eval.ops.size,
            ops = if (includeOps) eval.ops else null,
            localBoundingBox = localBounds,
            worldBoundingBox = worldBounds,
            totalBlocks = eval.grid.totalBlocks,
            materials = materials,
            survivalCheck = computeSurvivalCheck(materials),
        )
    }

    fun list(): BlueprintListResult = store.list()

    private suspend fun toMutationResult(eval: BlueprintSessionStore.SessionEvaluation): BlueprintMutationResult {
        val materials = eval.grid.materialCounts()
        return BlueprintMutationResult(
            blueprintId = eval.blueprintId,
            addedOpIds = eval.addedOpIds,
            updatedOpId = eval.updatedOpId,
            removedOpIds = eval.removedOpIds,
            totalOps = eval.ops.size,
            boundingBox = eval.grid.enclosingBounds(),
            totalBlocks = eval.grid.totalBlocks,
            materials = materials,
            survivalCheck = computeSurvivalCheck(materials),
        )
    }

    private suspend fun computeSurvivalCheck(materials: Map<String, Int>): SurvivalMaterialCheck? {
        val snapshot = runCatching { survivalProvider.getSnapshotOrNull() }.getOrNull() ?: return null
        if (!snapshot.gameMode.equals("survival", ignoreCase = true)) {
            return null
        }
        val available = HashMap<String, Int>()
        for (entry in snapshot.inventory) {
            available[entry.itemId] = (available[entry.itemId] ?: 0) + entry.count
        }
        val missing = LinkedHashMap<String, Int>()
        for ((blockId, needed) in materials) {
            val have = available[blockId] ?: 0
            if (needed > have) {
                missing[blockId] = needed - have
            }
        }
        return SurvivalMaterialCheck(
            gameMode = snapshot.gameMode.lowercase(),
            missingMaterials = missing,
        )
    }
}
