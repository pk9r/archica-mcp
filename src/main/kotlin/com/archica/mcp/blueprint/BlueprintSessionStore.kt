package com.archica.mcp.blueprint

import com.archica.mcp.context.BlockPos3i
import com.archica.mcp.context.CardinalDirection
import com.archica.mcp.context.ContextCompressionException
import com.archica.mcp.context.InventoryEntry
import com.archica.mcp.context.WorldContextSource
import com.archica.mcp.litematica.LitematicSchematicCompiler
import com.archica.mcp.litematica.LitematicaBridge
import com.archica.mcp.litematica.LitematicaUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
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
    val phases: List<BlueprintPhaseSummary> = emptyList(),
    val phaseView: PhaseViewState? = null,
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
    val phases: List<BlueprintPhaseSummary> = emptyList(),
    val phaseView: PhaseViewState? = null,
)

data class BlueprintListItem(
    val blueprintId: String,
    val origin: BlockPos3i,
    val facing: CardinalDirection,
    val opCount: Int,
    val hasActivePlacement: Boolean,
    val phaseCount: Int = 0,
    val activePhaseId: String? = null,
    val activePhaseTitle: String? = null,
    val viewMode: PhaseViewMode? = null,
)

data class BlueprintListResult(
    val blueprints: List<BlueprintListItem>,
)

data class BlueprintCompileRequest(
    val blueprintId: String,
    val activate: Boolean = true,
    val originOverride: BlockPos3i? = null,
)

data class BlueprintCompileResult(
    val blueprintId: String,
    val schematicPath: String,
    val activated: Boolean,
    val replacedExistingPlacement: Boolean,
    val worldOrigin: BlockPos3i,
    val enclosingSize: BlockPos3i,
    val totalBlocks: Int,
    val survivalCheck: SurvivalMaterialCheck? = null,
    val phaseCount: Int = 0,
    val activePhaseId: String? = null,
    val viewMode: PhaseViewMode? = null,
)

data class BlueprintPhaseNavigationResult(
    val blueprintId: String,
    val activePhase: BlueprintPhaseSummary,
    val activePhaseIndex: Int,
    val totalPhases: Int,
    val viewMode: PhaseViewMode,
    val visibleRegionNames: Set<String>,
    val placementUpdated: Boolean,
)

data class BlueprintPhaseOverview(
    val blueprintId: String,
    val phases: List<BlueprintPhaseSummary>,
    val phaseView: PhaseViewState,
    val hasActivePlacement: Boolean,
)

data class BlueprintClearPlacementRequest(
    val blueprintId: String,
    val deleteFile: Boolean = false,
)

data class BlueprintClearPlacementResult(
    val blueprintId: String,
    val cleared: Boolean,
    val deletedFile: Boolean,
)

/** Lifecycle notifications emitted when an MCP call activates or clears a Litematica placement. */
sealed interface PlacementFeedbackEvent {
    data class Activated(
        val blueprintId: String,
        val totalBlocks: Int,
        val worldOrigin: BlockPos3i,
        val replacedExisting: Boolean,
    ) : PlacementFeedbackEvent

    data class Cleared(
        val blueprintId: String,
        val deletedFile: Boolean,
    ) : PlacementFeedbackEvent
}

/**
 * Thread-safe in-memory store for stateful blueprint draft sessions with monotonic `op_id` allocation.
 */
class BlueprintSessionStore(
    catalog: BlockCatalog,
    private val activePlacementChecker: (String) -> Boolean = { false },
    private val draftStore: BlueprintDraftStore? = null,
    maxVolume: Long = VoxelGrid.MAX_RASTERIZED_VOLUME,
) {
    private val logger = LoggerFactory.getLogger(BlueprintSessionStore::class.java)
    private val rasterizer = BlueprintRasterizer(catalog = catalog, maxVolume = maxVolume)
    private val sessions = ConcurrentHashMap<String, MutableSession>()

    private class MutableSession(
        val blueprintId: String,
        val origin: BlockPos3i,
        val facing: CardinalDirection,
        val ops: MutableList<StoredBlueprintOp> = mutableListOf(),
        var nextOpNumber: Int = 1,
        var placement: PersistedPlacementState = PersistedPlacementState(),
        val phases: MutableList<BlueprintPhase> = mutableListOf(),
        var phaseView: PhaseViewState = PhaseViewState(),
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
        synchronized(created) {
            persistLocked(created)
        }
        return BlueprintCreateResult(
            blueprintId = request.blueprintId,
            origin = request.origin,
            facing = request.facing,
            opCount = 0,
        )
    }

    fun addOps(
        blueprintId: String,
        ops: List<PrimitiveOp>,
        batchPhase: BlueprintPhaseInput? = null,
        perOpPhases: List<BlueprintPhaseInput?>? = null,
    ): SessionEvaluation {
        if (ops.isEmpty()) {
            throw BlueprintException(BlueprintErrorCode.INVALID_ARGUMENTS, "Parameter 'ops' must contain at least one operation.")
        }
        if (perOpPhases != null && perOpPhases.size != ops.size) {
            throw BlueprintException(BlueprintErrorCode.INVALID_ARGUMENTS, "perOpPhases size must match ops size.")
        }
        ops.forEach { rasterizer.validate(it) }

        val session = requireSession(blueprintId)
        return synchronized(session) {
            val candidatePhases = session.phases.toMutableList()
            var nextNum = session.nextOpNumber
            val stagedNewOps = ArrayList<StoredBlueprintOp>(ops.size)
            val addedIds = ArrayList<String>(ops.size)

            for (index in ops.indices) {
                val opInput = perOpPhases?.get(index) ?: batchPhase
                val resolvedPhaseId = opInput?.let { resolveOrRegisterPhase(candidatePhases, it) }
                val opId = "op_${nextNum++}"
                stagedNewOps.add(StoredBlueprintOp(opId = opId, op = ops[index], phaseId = resolvedPhaseId))
                addedIds.add(opId)
            }

            val candidateOps = session.ops + stagedNewOps
            val grid = rasterizer.rasterizeStored(candidateOps)
            normalizeUnattributedVoxels(candidatePhases, grid)

            session.phases.clear()
            session.phases.addAll(candidatePhases)
            session.ops.addAll(stagedNewOps)
            session.nextOpNumber = nextNum

            val evaluation = buildEvaluationLocked(session, grid, addedOpIds = addedIds)
            persistLocked(session)
            evaluation
        }
    }

    fun updateOp(
        blueprintId: String,
        opId: String,
        op: PrimitiveOp,
        phase: BlueprintPhaseInput? = null,
    ): SessionEvaluation {
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
            val existing = session.ops[index]
            val candidatePhases = session.phases.toMutableList()
            val resolvedPhaseId = if (phase != null) {
                resolveOrRegisterPhase(candidatePhases, phase)
            } else {
                existing.phaseId
            }
            val updatedStored = StoredBlueprintOp(opId = opId, op = op, phaseId = resolvedPhaseId)
            val candidateOps = session.ops.mapIndexed { i, stored -> if (i == index) updatedStored else stored }
            val grid = rasterizer.rasterizeStored(candidateOps)
            normalizeUnattributedVoxels(candidatePhases, grid)

            session.phases.clear()
            session.phases.addAll(candidatePhases)
            session.ops[index] = updatedStored

            val evaluation = buildEvaluationLocked(session, grid, updatedOpId = opId)
            persistLocked(session)
            evaluation
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
            val candidateOps = session.ops.filterIndexed { i, _ -> i != index }
            val candidatePhases = session.phases.toMutableList()
            val grid = rasterizer.rasterizeStored(candidateOps)
            normalizeUnattributedVoxels(candidatePhases, grid)

            session.phases.clear()
            session.phases.addAll(candidatePhases)
            session.ops.removeAt(index)

            val evaluation = buildEvaluationLocked(session, grid, removedOpIds = listOf(opId))
            persistLocked(session)
            evaluation
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
            val candidatePhases = session.phases.toMutableList()
            val grid = rasterizer.rasterizeStored(remaining)
            normalizeUnattributedVoxels(candidatePhases, grid)

            session.phases.clear()
            session.phases.addAll(candidatePhases)
            repeat(removeCount) { session.ops.removeAt(session.ops.lastIndex) }

            val evaluation = buildEvaluationLocked(session, grid, removedOpIds = removedIds)
            if (removeCount > 0) {
                persistLocked(session)
            }
            evaluation
        }
    }

    fun setActivePhase(blueprintId: String, target: String): SessionEvaluation {
        val session = requireSession(blueprintId)
        return synchronized(session) {
            val eval = evaluateLocked(session)
            val activePhases = eval.nonEmptyPhases
            if (activePhases.isEmpty()) {
                throw BlueprintException(
                    BlueprintErrorCode.INVALID_ARGUMENTS,
                    "Blueprint '$blueprintId' has no build phases.",
                )
            }
            val trimmed = target.trim()
            val currentIndex = activePhases.indexOfFirst { it.phaseId == eval.phaseView.activePhaseId }
                .let { if (it < 0) 0 else it }
            val nextPhase = when {
                trimmed.equals("next", ignoreCase = true) -> {
                    activePhases[(currentIndex + 1).coerceAtMost(activePhases.lastIndex)]
                }
                trimmed.equals("prev", ignoreCase = true) || trimmed.equals("previous", ignoreCase = true) -> {
                    activePhases[(currentIndex - 1).coerceAtLeast(0)]
                }
                trimmed.toIntOrNull() != null -> {
                    val idx = trimmed.toInt()
                    if (idx !in 1..activePhases.size) {
                        throw BlueprintException(
                            BlueprintErrorCode.INVALID_ARGUMENTS,
                            "Phase index $idx out of range 1..${activePhases.size} for blueprint '$blueprintId'.",
                        )
                    }
                    activePhases[idx - 1]
                }
                else -> {
                    activePhases.firstOrNull { it.phaseId.equals(trimmed, ignoreCase = true) }
                        ?: throw BlueprintException(
                            BlueprintErrorCode.INVALID_ARGUMENTS,
                            "Phase '$trimmed' not found in blueprint '$blueprintId'.",
                        )
                }
            }
            session.phaseView = session.phaseView.copy(activePhaseId = nextPhase.phaseId)
            persistLocked(session)
            eval.copy(phaseView = session.phaseView)
        }
    }

    fun setPhaseViewMode(blueprintId: String, mode: PhaseViewMode?): SessionEvaluation {
        val session = requireSession(blueprintId)
        return synchronized(session) {
            val eval = evaluateLocked(session)
            if (eval.nonEmptyPhases.isEmpty()) {
                throw BlueprintException(
                    BlueprintErrorCode.INVALID_ARGUMENTS,
                    "Blueprint '$blueprintId' has no build phases.",
                )
            }
            val nextMode = mode ?: eval.phaseView.viewMode.nextToggle()
            session.phaseView = eval.phaseView.copy(viewMode = nextMode)
            persistLocked(session)
            eval.copy(phaseView = session.phaseView)
        }
    }

    fun updatePlacementState(
        blueprintId: String,
        activated: Boolean,
        worldOrigin: BlockPos3i? = null,
        compiledAt: Long? = null,
    ) {
        val session = sessions[blueprintId] ?: return
        synchronized(session) {
            session.placement = PersistedPlacementState(
                activated = activated,
                lastWorldOrigin = worldOrigin?.let { PersistedVec3i.from(it) } ?: session.placement.lastWorldOrigin,
                lastCompiledAt = compiledAt ?: session.placement.lastCompiledAt,
            )
            persistLocked(session)
        }
    }

    fun loadPersistedDrafts(): Int {
        val store = draftStore ?: return 0
        val drafts = store.loadAll()
        var loadedCount = 0
        for (draft in drafts) {
            try {
                val facing = draft.parsedFacing()
                val domainPhases = draft.phases.map { it.toDomain() }.sortedBy { it.order }
                val domainOps = draft.ops.map { it.toDomain() }
                domainOps.forEach { rasterizer.validate(it.op) }
                val grid = rasterizer.rasterizeStored(domainOps)
                val mutablePhases = domainPhases.toMutableList()
                normalizeUnattributedVoxels(mutablePhases, grid)
                val maxExistingNumber = domainOps
                    .mapNotNull { parseNumericOpId(it.opId) }
                    .maxOrNull() ?: 0
                val safeNextOpNumber = maxOf(draft.nextOpNumber, maxExistingNumber + 1)
                val session = MutableSession(
                    blueprintId = draft.blueprintId,
                    origin = draft.origin.toBlockPos3i(),
                    facing = facing,
                    ops = domainOps.toMutableList(),
                    nextOpNumber = safeNextOpNumber,
                    placement = draft.placement,
                    phases = mutablePhases,
                    phaseView = draft.phaseView?.toDomain() ?: PhaseViewState(),
                )
                reconcilePhaseViewLocked(session, grid)
                sessions[draft.blueprintId] = session
                loadedCount++
            } catch (t: Throwable) {
                logger.warn(
                    "Skipping invalid persisted draft '{}' during startup reload: {}",
                    draft.blueprintId,
                    t.message ?: t::class.simpleName,
                    t,
                )
            }
        }
        return loadedCount
    }

    private fun persistLocked(session: MutableSession) {
        val store = draftStore ?: return
        val draft = PersistedBlueprintDraft(
            schemaVersion = PersistedBlueprintDraft.CURRENT_SCHEMA_VERSION,
            blueprintId = session.blueprintId,
            origin = PersistedVec3i.from(session.origin),
            facing = session.facing.name,
            nextOpNumber = session.nextOpNumber,
            ops = session.ops.map { PersistedBlueprintOp.fromDomain(it) },
            placement = session.placement,
            phases = session.phases.sortedBy { it.order }.map { PersistedPhase.fromDomain(it) },
            phaseView = if (session.phases.isEmpty()) null else PersistedPhaseViewState.fromDomain(session.phaseView),
        )
        store.save(draft)
    }

    private fun parseNumericOpId(opId: String): Int? {
        if (!opId.startsWith("op_")) return null
        return opId.removePrefix("op_").toIntOrNull()
    }

    fun inspect(blueprintId: String): SessionEvaluation {
        val session = requireSession(blueprintId)
        return synchronized(session) {
            evaluateLocked(session)
        }
    }

    fun sessionCount(): Int = sessions.size

    fun sessionIds(): List<String> = sessions.keys.sorted()

    fun list(activePlacementIds: Set<String>? = null): BlueprintListResult {
        val items = sessions.values.map { session ->
            synchronized(session) {
                val activePhase = session.phaseView.activePhaseId?.let { activeId ->
                    session.phases.firstOrNull { it.phaseId == activeId }
                }
                BlueprintListItem(
                    blueprintId = session.blueprintId,
                    origin = session.origin,
                    facing = session.facing,
                    opCount = session.ops.size,
                    hasActivePlacement = activePlacementIds?.contains(session.blueprintId)
                        ?: activePlacementChecker(session.blueprintId),
                    phaseCount = session.phases.size,
                    activePhaseId = activePhase?.phaseId,
                    activePhaseTitle = activePhase?.title,
                    viewMode = if (session.phases.isEmpty()) null else session.phaseView.viewMode,
                )
            }
        }.sortedBy { it.blueprintId }
        return BlueprintListResult(items)
    }

    private fun evaluateLocked(session: MutableSession): SessionEvaluation {
        val grid = rasterizer.rasterizeStored(session.ops)
        val candidatePhases = session.phases.toMutableList()
        normalizeUnattributedVoxels(candidatePhases, grid)
        session.phases.clear()
        session.phases.addAll(candidatePhases)
        return buildEvaluationLocked(session, grid)
    }

    private fun buildEvaluationLocked(
        session: MutableSession,
        grid: VoxelGrid,
        addedOpIds: List<String> = emptyList(),
        updatedOpId: String? = null,
        removedOpIds: List<String> = emptyList(),
    ): SessionEvaluation {
        val orderedPhases = session.phases.sortedBy { it.order }
        val nonEmptyPhases = orderedPhases.filter { grid.blockCountForPhase(it.phaseId) > 0 }
        reconcilePhaseViewLocked(session, grid, nonEmptyPhases)
        return SessionEvaluation(
            blueprintId = session.blueprintId,
            origin = session.origin,
            facing = session.facing,
            addedOpIds = addedOpIds,
            updatedOpId = updatedOpId,
            removedOpIds = removedOpIds,
            ops = session.ops.toList(),
            grid = grid,
            phases = orderedPhases,
            nonEmptyPhases = nonEmptyPhases,
            phaseView = session.phaseView,
        )
    }

    private fun reconcilePhaseViewLocked(
        session: MutableSession,
        grid: VoxelGrid,
        nonEmptyPhases: List<BlueprintPhase> = session.phases
            .sortedBy { it.order }
            .filter { grid.blockCountForPhase(it.phaseId) > 0 },
    ) {
        if (nonEmptyPhases.isEmpty()) {
            session.phaseView = PhaseViewState(activePhaseId = null, viewMode = session.phaseView.viewMode)
            return
        }
        val currentId = session.phaseView.activePhaseId
        if (currentId != null && nonEmptyPhases.any { it.phaseId == currentId }) {
            return
        }
        if (currentId != null) {
            val oldOrder = session.phases.firstOrNull { it.phaseId == currentId }?.order
            val clamped = nonEmptyPhases.lastOrNull { oldOrder != null && it.order <= oldOrder }
                ?: nonEmptyPhases.first()
            session.phaseView = session.phaseView.copy(activePhaseId = clamped.phaseId)
            return
        }
        session.phaseView = PhaseViewState(
            activePhaseId = nonEmptyPhases.first().phaseId,
            viewMode = PhaseViewMode.ISOLATED,
        )
    }

    private fun resolveOrRegisterPhase(
        phases: MutableList<BlueprintPhase>,
        input: BlueprintPhaseInput,
    ): String {
        val trimmedTitle = input.title.trim()
        if (trimmedTitle.isEmpty()) {
            throw BlueprintException(BlueprintErrorCode.INVALID_ARGUMENTS, "Phase 'title' cannot be blank.")
        }
        val cleanedDesc = input.description?.trim()?.takeIf { it.isNotEmpty() }
        val requestedId = input.phaseId?.trim()
        if (requestedId != null) {
            BlueprintPhase.requireValidPhaseId(requestedId)
            val existingIdx = phases.indexOfFirst { it.phaseId == requestedId }
            if (existingIdx >= 0) {
                val existing = phases[existingIdx]
                if (cleanedDesc != null && existing.description == null) {
                    phases[existingIdx] = existing.copy(description = cleanedDesc)
                }
                return existing.phaseId
            }
            val nextOrder = (phases.maxOfOrNull { it.order } ?: 0) + 1
            phases.add(
                BlueprintPhase(
                    phaseId = requestedId,
                    order = nextOrder,
                    title = trimmedTitle,
                    description = cleanedDesc,
                ),
            )
            return requestedId
        }

        val matchingByTitle = phases.indexOfFirst { it.title.equals(trimmedTitle, ignoreCase = true) }
        if (matchingByTitle >= 0) {
            val existing = phases[matchingByTitle]
            if (cleanedDesc != null && existing.description == null) {
                phases[matchingByTitle] = existing.copy(description = cleanedDesc)
            }
            return existing.phaseId
        }

        val baseSlug = BlueprintPhase.slugify(trimmedTitle)
        var candidateId = baseSlug
        var suffix = 2
        while (phases.any { it.phaseId == candidateId }) {
            val suffixStr = "_$suffix"
            val maxBase = (32 - suffixStr.length).coerceAtLeast(1)
            candidateId = baseSlug.take(maxBase) + suffixStr
            suffix++
        }
        val nextOrder = (phases.maxOfOrNull { it.order } ?: 0) + 1
        phases.add(
            BlueprintPhase(
                phaseId = candidateId,
                order = nextOrder,
                title = trimmedTitle,
                description = cleanedDesc,
            ),
        )
        return candidateId
    }

    private fun normalizeUnattributedVoxels(phases: MutableList<BlueprintPhase>, grid: VoxelGrid) {
        if (phases.isEmpty() || grid.blockCountForPhase(null) == 0) return
        val existingGeneral = phases.firstOrNull { it.phaseId == "general" }
        val targetId = if (existingGeneral != null) {
            existingGeneral.phaseId
        } else {
            val nextOrder = (phases.maxOfOrNull { it.order } ?: 0) + 1
            phases.add(BlueprintPhase(phaseId = "general", order = nextOrder, title = "General"))
            "general"
        }
        grid.assignUnattributedPhase(targetId)
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
        val phases: List<BlueprintPhase> = emptyList(),
        val nonEmptyPhases: List<BlueprintPhase> = emptyList(),
        val phaseView: PhaseViewState = PhaseViewState(),
    )

    companion object {
        private val BLUEPRINT_ID_REGEX = Regex("^[a-z0-9_-]{1,48}$")

        fun requireValidBlueprintId(blueprintId: String) {
            if (!BLUEPRINT_ID_REGEX.matches(blueprintId)) {
                throw BlueprintException(
                    BlueprintErrorCode.INVALID_ARGUMENTS,
                    "Invalid blueprint_id '$blueprintId': must match ^[a-z0-9_-]{1,48}$.",
                )
            }
        }

        /** Computes enabled `.litematic` subregion names for [nonEmptyPhases] and [phaseView]. */
        fun visibleRegionNames(nonEmptyPhases: List<BlueprintPhase>, phaseView: PhaseViewState): Set<String> {
            if (nonEmptyPhases.size <= 1) return emptySet()
            val activeIndex = nonEmptyPhases.indexOfFirst { it.phaseId == phaseView.activePhaseId }
                .let { if (it < 0) 0 else it }
            return when (phaseView.viewMode) {
                PhaseViewMode.ISOLATED -> setOf(
                    BlueprintPhase.regionName(activeIndex + 1, nonEmptyPhases[activeIndex].phaseId),
                )
                PhaseViewMode.CUMULATIVE -> (0..activeIndex).mapTo(LinkedHashSet()) { idx ->
                    BlueprintPhase.regionName(idx + 1, nonEmptyPhases[idx].phaseId)
                }
                PhaseViewMode.ALL -> nonEmptyPhases.mapIndexedTo(LinkedHashSet()) { idx, phase ->
                    BlueprintPhase.regionName(idx + 1, phase.phaseId)
                }
            }
        }
    }
}

/**
 * Coordinates [BlueprintSessionStore] mutations/inspections with optional survival material checks
 * and `.litematic` compilation / placement via [LitematicaBridge].
 */
class BlueprintService(
    catalog: BlockCatalog,
    private val survivalProvider: SurvivalSnapshotProvider = SurvivalSnapshotProvider { null },
    private val activePlacementChecker: ((String) -> Boolean)? = null,
    private val litematicaBridge: LitematicaBridge? = null,
    private val compiler: LitematicSchematicCompiler = LitematicSchematicCompiler(),
    private val draftStore: BlueprintDraftStore? = null,
    maxVolume: Long = VoxelGrid.MAX_RASTERIZED_VOLUME,
    private val placementFeedbackListener: ((PlacementFeedbackEvent) -> Unit)? = null,
) {
    private val store = BlueprintSessionStore(
        catalog = catalog,
        activePlacementChecker = activePlacementChecker ?: { false },
        draftStore = draftStore,
        maxVolume = maxVolume,
    )

    fun loadPersistedDrafts(): Int = store.loadPersistedDrafts()

    fun sessionCount(): Int = store.sessionCount()

    fun sessionIds(): List<String> = store.sessionIds()

    fun create(request: BlueprintCreateRequest): BlueprintCreateResult = store.create(request)

    suspend fun addOps(
        blueprintId: String,
        ops: List<PrimitiveOp>,
        batchPhase: BlueprintPhaseInput? = null,
        perOpPhases: List<BlueprintPhaseInput?>? = null,
    ): BlueprintMutationResult =
        toMutationResult(store.addOps(blueprintId, ops, batchPhase, perOpPhases))

    suspend fun updateOp(
        blueprintId: String,
        opId: String,
        op: PrimitiveOp,
        phase: BlueprintPhaseInput? = null,
    ): BlueprintMutationResult =
        toMutationResult(store.updateOp(blueprintId, opId, op, phase))

    suspend fun removeOp(blueprintId: String, opId: String): BlueprintMutationResult =
        toMutationResult(store.removeOp(blueprintId, opId))

    suspend fun undo(blueprintId: String, count: Int = 1): BlueprintMutationResult =
        toMutationResult(store.undo(blueprintId, count))

    suspend fun inspect(blueprintId: String, includeOps: Boolean = true): BlueprintInspectResult {
        val eval = store.inspect(blueprintId)
        val localBounds = eval.grid.enclosingBounds()
        val worldBounds = localBounds?.let { BlueprintTransform.toWorldBounds(it, eval.origin, eval.facing) }
        val materials = eval.grid.materialCounts()
        val snapshot = fetchSurvivalSnapshotOrNull()
        val phaseSummaries = buildPhaseSummaries(eval, snapshot)
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
            survivalCheck = computeSurvivalCheck(materials, snapshot),
            phases = phaseSummaries,
            phaseView = if (eval.nonEmptyPhases.isEmpty()) null else eval.phaseView,
        )
    }

    suspend fun setActivePhase(blueprintId: String, target: String): BlueprintPhaseNavigationResult {
        val eval = store.setActivePhase(blueprintId, target)
        return applyPhaseVisibilityUpdate(eval)
    }

    suspend fun stepPhase(blueprintId: String, delta: Int): BlueprintPhaseNavigationResult {
        val target = if (delta >= 0) "next" else "prev"
        return setActivePhase(blueprintId, target)
    }

    suspend fun setPhaseViewMode(blueprintId: String, mode: PhaseViewMode): BlueprintPhaseNavigationResult {
        val eval = store.setPhaseViewMode(blueprintId, mode)
        return applyPhaseVisibilityUpdate(eval)
    }

    suspend fun togglePhaseViewMode(blueprintId: String): BlueprintPhaseNavigationResult {
        val eval = store.setPhaseViewMode(blueprintId, null)
        return applyPhaseVisibilityUpdate(eval)
    }

    private suspend fun applyPhaseVisibilityUpdate(eval: BlueprintSessionStore.SessionEvaluation): BlueprintPhaseNavigationResult {
        val visibleRegions = BlueprintSessionStore.visibleRegionNames(eval.nonEmptyPhases, eval.phaseView)
        val bridge = litematicaBridge
        val placementUpdated = if (bridge != null && visibleRegions.isNotEmpty()) {
            runCatching { bridge.setSubRegionVisibility(eval.blueprintId, visibleRegions) }
                .getOrElse { ex ->
                    if (ex is LitematicaUnavailableException) throw ex
                    false
                }
        } else {
            false
        }
        val activeIndex = eval.nonEmptyPhases.indexOfFirst { it.phaseId == eval.phaseView.activePhaseId }
            .let { if (it < 0) 0 else it }
        val activePhase = eval.nonEmptyPhases[activeIndex]
        val snapshot = fetchSurvivalSnapshotOrNull()
        val materials = eval.grid.materialCountsForPhase(activePhase.phaseId)
        val summary = BlueprintPhaseSummary(
            phaseId = activePhase.phaseId,
            order = activeIndex + 1,
            title = activePhase.title,
            description = activePhase.description,
            totalBlocks = eval.grid.blockCountForPhase(activePhase.phaseId),
            materials = materials,
            survivalCheck = computeSurvivalCheck(materials, snapshot),
        )
        return BlueprintPhaseNavigationResult(
            blueprintId = eval.blueprintId,
            activePhase = summary,
            activePhaseIndex = activeIndex + 1,
            totalPhases = eval.nonEmptyPhases.size,
            viewMode = eval.phaseView.viewMode,
            visibleRegionNames = visibleRegions,
            placementUpdated = placementUpdated,
        )
    }

    suspend fun getPhaseOverview(blueprintId: String): BlueprintPhaseOverview {
        val eval = store.inspect(blueprintId)
        val snapshot = fetchSurvivalSnapshotOrNull()
        val activeIds = activePlacementIds()
        return BlueprintPhaseOverview(
            blueprintId = eval.blueprintId,
            phases = buildPhaseSummaries(eval, snapshot),
            phaseView = eval.phaseView,
            hasActivePlacement = blueprintId in activeIds,
        )
    }

    suspend fun list(): BlueprintListResult {
        if (activePlacementChecker != null) {
            return store.list()
        }
        val activeIds = litematicaBridge?.let { bridge ->
            runCatching { bridge.listActivePlacements() }.getOrElse { emptySet() }
        } ?: emptySet()
        return store.list(activeIds)
    }

    /** Resolves the preferred active phased blueprint (or latest phased draft), or null if none exist. */
    suspend fun resolvePhasedBlueprintId(preferredId: String? = null): String? {
        val phased = list().blueprints.filter { it.phaseCount > 0 }
        if (phased.isEmpty()) return null
        val chosen = phased.firstOrNull { it.blueprintId == preferredId && it.hasActivePlacement }
            ?: phased.firstOrNull { it.hasActivePlacement }
            ?: phased.firstOrNull { it.blueprintId == preferredId }
            ?: phased.first()
        return chosen.blueprintId
    }

    private suspend fun activePlacementIds(): Set<String> {
        val checker = activePlacementChecker
        if (checker != null) {
            return store.sessionIds().filterTo(HashSet()) { checker.invoke(it) }
        }
        return litematicaBridge?.let { bridge ->
            runCatching { bridge.listActivePlacements() }.getOrElse { emptySet() }
        } ?: emptySet()
    }

    suspend fun compile(request: BlueprintCompileRequest): BlueprintCompileResult {
        BlueprintSessionStore.requireValidBlueprintId(request.blueprintId)
        val eval = store.inspect(request.blueprintId)
        if (eval.grid.totalBlocks == 0) {
            throw BlueprintException(
                BlueprintErrorCode.INVALID_ARGUMENTS,
                "Cannot compile empty blueprint '${request.blueprintId}' with 0 non-air blocks.",
            )
        }

        val bridge = litematicaBridge
            ?: throw LitematicaUnavailableException("Litematica bridge is not configured.")
        val schematicsDir = resolveSchematicsDir(bridge)
        val compiled = withContext(Dispatchers.IO) {
            compiler.compileToFile(
                blueprintId = eval.blueprintId,
                sessionOrigin = eval.origin,
                facing = eval.facing,
                grid = eval.grid,
                schematicsBaseDir = schematicsDir,
                originOverride = request.originOverride,
                phases = eval.nonEmptyPhases,
            )
        }
        val compiledAt = System.currentTimeMillis()
        store.updatePlacementState(
            blueprintId = eval.blueprintId,
            activated = false,
            worldOrigin = compiled.worldOrigin,
            compiledAt = compiledAt,
        )

        val initialVisibleRegions = BlueprintSessionStore.visibleRegionNames(eval.nonEmptyPhases, eval.phaseView)
            .takeIf { it.isNotEmpty() }
        var activated = false
        var replacedExisting = false
        if (request.activate) {
            try {
                replacedExisting = bridge.activatePlacement(
                    blueprintId = eval.blueprintId,
                    schematicFile = compiled.filePath,
                    worldOrigin = compiled.worldOrigin,
                    visibleRegions = initialVisibleRegions,
                )
                activated = true
                store.updatePlacementState(
                    blueprintId = eval.blueprintId,
                    activated = true,
                    worldOrigin = compiled.worldOrigin,
                    compiledAt = compiledAt,
                )
                placementFeedbackListener?.invoke(
                    PlacementFeedbackEvent.Activated(
                        blueprintId = eval.blueprintId,
                        totalBlocks = compiled.totalBlocks,
                        worldOrigin = compiled.worldOrigin,
                        replacedExisting = replacedExisting,
                    ),
                )
            } catch (e: LitematicaUnavailableException) {
                val msg = if (e.message.contains(compiled.schematicPath)) {
                    e.message
                } else {
                    "${e.message} (compiled schematic saved at '${compiled.schematicPath}')"
                }
                throw LitematicaUnavailableException(msg, schematicPath = compiled.schematicPath, cause = e)
            } catch (e: ContextCompressionException) {
                throw e
            } catch (e: TimeoutCancellationException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                throw LitematicaUnavailableException(
                    "Failed to activate Litematica placement for '${eval.blueprintId}': " +
                        "${t.message ?: t::class.simpleName} (compiled schematic saved at '${compiled.schematicPath}')",
                    schematicPath = compiled.schematicPath,
                    cause = t,
                )
            }
        }

        val materials = eval.grid.materialCounts()
        val snapshot = fetchSurvivalSnapshotOrNull()
        return BlueprintCompileResult(
            blueprintId = eval.blueprintId,
            schematicPath = compiled.schematicPath,
            activated = activated,
            replacedExistingPlacement = replacedExisting,
            worldOrigin = compiled.worldOrigin,
            enclosingSize = compiled.enclosingSize,
            totalBlocks = compiled.totalBlocks,
            survivalCheck = computeSurvivalCheck(materials, snapshot),
            phaseCount = eval.nonEmptyPhases.size,
            activePhaseId = if (eval.nonEmptyPhases.isEmpty()) null else eval.phaseView.activePhaseId,
            viewMode = if (eval.nonEmptyPhases.isEmpty()) null else eval.phaseView.viewMode,
        )
    }

    suspend fun clearPlacement(request: BlueprintClearPlacementRequest): BlueprintClearPlacementResult {
        BlueprintSessionStore.requireValidBlueprintId(request.blueprintId)
        val bridge = litematicaBridge
            ?: throw LitematicaUnavailableException("Litematica bridge is not configured.")
        val cleared = try {
            bridge.clearPlacement(request.blueprintId)
        } catch (e: LitematicaUnavailableException) {
            throw e
        } catch (e: ContextCompressionException) {
            throw e
        } catch (e: TimeoutCancellationException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            throw LitematicaUnavailableException(
                "Failed to clear Litematica placement for '${request.blueprintId}': ${t.message ?: t::class.simpleName}",
                cause = t,
            )
        }

        var deletedFile = false
        if (request.deleteFile) {
            val schematicsDir = resolveSchematicsDir(bridge)
            val filePath = schematicsDir
                .resolve(LitematicSchematicCompiler.ARCHICA_SUBDIR)
                .resolve("${request.blueprintId}${LitematicSchematicCompiler.LITEMATIC_EXTENSION}")
            runCatching { bridge.evictCachedSchematic(filePath) }
            deletedFile = withContext(Dispatchers.IO) { Files.deleteIfExists(filePath) }
        }
        store.updatePlacementState(
            blueprintId = request.blueprintId,
            activated = false,
        )
        if (cleared) {
            placementFeedbackListener?.invoke(
                PlacementFeedbackEvent.Cleared(
                    blueprintId = request.blueprintId,
                    deletedFile = deletedFile,
                ),
            )
        }
        return BlueprintClearPlacementResult(
            blueprintId = request.blueprintId,
            cleared = cleared,
            deletedFile = deletedFile,
        )
    }

    private fun resolveSchematicsDir(bridge: LitematicaBridge) = try {
        bridge.schematicsDirectory()
    } catch (e: LitematicaUnavailableException) {
        throw e
    } catch (t: Throwable) {
        throw LitematicaUnavailableException(
            "Unable to resolve Litematica schematics directory: ${t.message ?: t::class.simpleName}",
            cause = t,
        )
    }

    private suspend fun toMutationResult(eval: BlueprintSessionStore.SessionEvaluation): BlueprintMutationResult {
        val materials = eval.grid.materialCounts()
        val snapshot = fetchSurvivalSnapshotOrNull()
        return BlueprintMutationResult(
            blueprintId = eval.blueprintId,
            addedOpIds = eval.addedOpIds,
            updatedOpId = eval.updatedOpId,
            removedOpIds = eval.removedOpIds,
            totalOps = eval.ops.size,
            boundingBox = eval.grid.enclosingBounds(),
            totalBlocks = eval.grid.totalBlocks,
            materials = materials,
            survivalCheck = computeSurvivalCheck(materials, snapshot),
            phases = buildPhaseSummaries(eval, snapshot),
            phaseView = if (eval.nonEmptyPhases.isEmpty()) null else eval.phaseView,
        )
    }

    private fun buildPhaseSummaries(
        eval: BlueprintSessionStore.SessionEvaluation,
        snapshot: SurvivalInventorySnapshot?,
    ): List<BlueprintPhaseSummary> {
        if (eval.nonEmptyPhases.isEmpty()) return emptyList()
        return eval.nonEmptyPhases.mapIndexed { idx, phase ->
            val phaseMaterials = eval.grid.materialCountsForPhase(phase.phaseId)
            BlueprintPhaseSummary(
                phaseId = phase.phaseId,
                order = idx + 1,
                title = phase.title,
                description = phase.description,
                totalBlocks = eval.grid.blockCountForPhase(phase.phaseId),
                materials = phaseMaterials,
                survivalCheck = computeSurvivalCheck(phaseMaterials, snapshot),
            )
        }
    }

    private suspend fun fetchSurvivalSnapshotOrNull(): SurvivalInventorySnapshot? =
        runCatching { survivalProvider.getSnapshotOrNull() }.getOrNull()

    private fun computeSurvivalCheck(
        materials: Map<String, Int>,
        snapshot: SurvivalInventorySnapshot?,
    ): SurvivalMaterialCheck? {
        if (snapshot == null || !snapshot.gameMode.equals("survival", ignoreCase = true)) {
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
