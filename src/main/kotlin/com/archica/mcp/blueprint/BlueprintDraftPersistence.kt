package com.archica.mcp.blueprint

import com.archica.mcp.context.BlockPos3i
import com.archica.mcp.context.CardinalDirection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
data class PersistedVec3i(
    val x: Int,
    val y: Int,
    val z: Int,
) {
    fun toBlockPos3i(): BlockPos3i = BlockPos3i(x, y, z)

    companion object {
        fun from(pos: BlockPos3i): PersistedVec3i = PersistedVec3i(pos.x, pos.y, pos.z)
    }
}

@Serializable
data class PersistedPlacementState(
    val activated: Boolean = false,
    val lastWorldOrigin: PersistedVec3i? = null,
    val lastCompiledAt: Long? = null,
)

@Serializable
sealed interface PersistedPrimitiveOp {
    fun toDomain(): PrimitiveOp

    @Serializable
    @SerialName("set_block")
    data class SetBlock(
        val pos: PersistedVec3i,
        val block: String,
    ) : PersistedPrimitiveOp {
        override fun toDomain(): PrimitiveOp = PrimitiveOp.SetBlock(pos = pos.toBlockPos3i(), block = block)
    }

    @Serializable
    @SerialName("fill_box")
    data class FillBox(
        val from: PersistedVec3i,
        val to: PersistedVec3i,
        val block: String,
    ) : PersistedPrimitiveOp {
        override fun toDomain(): PrimitiveOp = PrimitiveOp.FillBox(from = from.toBlockPos3i(), to = to.toBlockPos3i(), block = block)
    }

    @Serializable
    @SerialName("hollow_box")
    data class HollowBox(
        val from: PersistedVec3i,
        val to: PersistedVec3i,
        val block: String,
        val wallsOnly: Boolean = false,
    ) : PersistedPrimitiveOp {
        override fun toDomain(): PrimitiveOp = PrimitiveOp.HollowBox(
            from = from.toBlockPos3i(),
            to = to.toBlockPos3i(),
            block = block,
            wallsOnly = wallsOnly,
        )
    }

    @Serializable
    @SerialName("line")
    data class Line(
        val from: PersistedVec3i,
        val to: PersistedVec3i,
        val block: String,
    ) : PersistedPrimitiveOp {
        override fun toDomain(): PrimitiveOp = PrimitiveOp.Line(from = from.toBlockPos3i(), to = to.toBlockPos3i(), block = block)
    }

    @Serializable
    @SerialName("cylinder")
    data class Cylinder(
        val center: PersistedVec3i,
        val radius: Int,
        val height: Int,
        val block: String,
        val hollow: Boolean = false,
    ) : PersistedPrimitiveOp {
        override fun toDomain(): PrimitiveOp = PrimitiveOp.Cylinder(
            center = center.toBlockPos3i(),
            radius = radius,
            height = height,
            block = block,
            hollow = hollow,
        )
    }

    @Serializable
    @SerialName("dome")
    data class Dome(
        val center: PersistedVec3i,
        val radius: Int,
        val block: String,
        val hollow: Boolean = true,
    ) : PersistedPrimitiveOp {
        override fun toDomain(): PrimitiveOp = PrimitiveOp.Dome(
            center = center.toBlockPos3i(),
            radius = radius,
            block = block,
            hollow = hollow,
        )
    }

    @Serializable
    @SerialName("gable_roof")
    data class GableRoof(
        val from: PersistedVec3i,
        val to: PersistedVec3i,
        val block: String,
        val ridgeBlock: String? = null,
        val ridgeAxis: String,
        val overhang: Int = 1,
    ) : PersistedPrimitiveOp {
        override fun toDomain(): PrimitiveOp {
            val axis = runCatching { RidgeAxis.valueOf(ridgeAxis.trim().uppercase()) }
                .getOrElse { throw IllegalArgumentException("Invalid ridgeAxis '$ridgeAxis'.") }
            return PrimitiveOp.GableRoof(
                from = from.toBlockPos3i(),
                to = to.toBlockPos3i(),
                block = block,
                ridgeBlock = ridgeBlock,
                ridgeAxis = axis,
                overhang = overhang,
            )
        }
    }

    @Serializable
    @SerialName("staircase")
    data class Staircase(
        val from: PersistedVec3i,
        val to: PersistedVec3i,
        val block: String,
        val width: Int = 1,
        val fillUnder: Boolean = false,
    ) : PersistedPrimitiveOp {
        override fun toDomain(): PrimitiveOp = PrimitiveOp.Staircase(
            from = from.toBlockPos3i(),
            to = to.toBlockPos3i(),
            block = block,
            width = width,
            fillUnder = fillUnder,
        )
    }

    @Serializable
    @SerialName("replace_box")
    data class ReplaceBox(
        val from: PersistedVec3i,
        val to: PersistedVec3i,
        val target: String,
        val replacement: String,
    ) : PersistedPrimitiveOp {
        override fun toDomain(): PrimitiveOp = PrimitiveOp.ReplaceBox(
            from = from.toBlockPos3i(),
            to = to.toBlockPos3i(),
            target = target,
            replacement = replacement,
        )
    }

    @Serializable
    @SerialName("clear_box")
    data class ClearBox(
        val from: PersistedVec3i,
        val to: PersistedVec3i,
    ) : PersistedPrimitiveOp {
        override fun toDomain(): PrimitiveOp = PrimitiveOp.ClearBox(
            from = from.toBlockPos3i(),
            to = to.toBlockPos3i(),
        )
    }

    @Serializable
    @SerialName("repeat_pattern")
    data class RepeatPattern(
        val op: PersistedPrimitiveOp,
        val count: Int,
        val step: PersistedVec3i,
    ) : PersistedPrimitiveOp {
        override fun toDomain(): PrimitiveOp = PrimitiveOp.RepeatPattern(
            op = op.toDomain(),
            count = count,
            step = step.toBlockPos3i(),
        )
    }

    companion object {
        fun fromDomain(op: PrimitiveOp): PersistedPrimitiveOp = when (op) {
            is PrimitiveOp.SetBlock -> SetBlock(PersistedVec3i.from(op.pos), op.block)
            is PrimitiveOp.FillBox -> FillBox(PersistedVec3i.from(op.from), PersistedVec3i.from(op.to), op.block)
            is PrimitiveOp.HollowBox -> HollowBox(PersistedVec3i.from(op.from), PersistedVec3i.from(op.to), op.block, op.wallsOnly)
            is PrimitiveOp.Line -> Line(PersistedVec3i.from(op.from), PersistedVec3i.from(op.to), op.block)
            is PrimitiveOp.Cylinder -> Cylinder(PersistedVec3i.from(op.center), op.radius, op.height, op.block, op.hollow)
            is PrimitiveOp.Dome -> Dome(PersistedVec3i.from(op.center), op.radius, op.block, op.hollow)
            is PrimitiveOp.GableRoof -> GableRoof(
                from = PersistedVec3i.from(op.from),
                to = PersistedVec3i.from(op.to),
                block = op.block,
                ridgeBlock = op.ridgeBlock,
                ridgeAxis = op.ridgeAxis.name,
                overhang = op.overhang,
            )
            is PrimitiveOp.Staircase -> Staircase(
                from = PersistedVec3i.from(op.from),
                to = PersistedVec3i.from(op.to),
                block = op.block,
                width = op.width,
                fillUnder = op.fillUnder,
            )
            is PrimitiveOp.ReplaceBox -> ReplaceBox(PersistedVec3i.from(op.from), PersistedVec3i.from(op.to), op.target, op.replacement)
            is PrimitiveOp.ClearBox -> ClearBox(PersistedVec3i.from(op.from), PersistedVec3i.from(op.to))
            is PrimitiveOp.RepeatPattern -> RepeatPattern(fromDomain(op.op), op.count, PersistedVec3i.from(op.step))
        }
    }
}

@Serializable
data class PersistedPhase(
    val phaseId: String,
    val order: Int,
    val title: String,
    val description: String? = null,
) {
    fun toDomain(): BlueprintPhase {
        BlueprintPhase.requireValidPhaseId(phaseId)
        require(order >= 1) { "Persisted phase order must be >= 1, got $order." }
        require(title.isNotBlank()) { "Persisted phase '$phaseId' title cannot be blank." }
        return BlueprintPhase(
            phaseId = phaseId,
            order = order,
            title = title,
            description = description,
        )
    }

    companion object {
        fun fromDomain(phase: BlueprintPhase): PersistedPhase = PersistedPhase(
            phaseId = phase.phaseId,
            order = phase.order,
            title = phase.title,
            description = phase.description,
        )
    }
}

@Serializable
data class PersistedPhaseViewState(
    val activePhaseId: String? = null,
    val viewMode: String = PhaseViewMode.ISOLATED.name,
) {
    fun toDomain(): PhaseViewState {
        if (activePhaseId != null) {
            BlueprintPhase.requireValidPhaseId(activePhaseId)
        }
        return PhaseViewState(
            activePhaseId = activePhaseId,
            viewMode = PhaseViewMode.parse(viewMode),
        )
    }

    companion object {
        fun fromDomain(state: PhaseViewState): PersistedPhaseViewState = PersistedPhaseViewState(
            activePhaseId = state.activePhaseId,
            viewMode = state.viewMode.name,
        )
    }
}

@Serializable
data class PersistedBlueprintOp(
    val opId: String,
    val op: PersistedPrimitiveOp,
    val phaseId: String? = null,
) {
    fun toDomain(): StoredBlueprintOp {
        require(opId.isNotBlank()) { "Persisted operation opId cannot be blank." }
        if (phaseId != null) {
            BlueprintPhase.requireValidPhaseId(phaseId)
        }
        return StoredBlueprintOp(opId = opId, op = op.toDomain(), phaseId = phaseId)
    }

    companion object {
        fun fromDomain(stored: StoredBlueprintOp): PersistedBlueprintOp =
            PersistedBlueprintOp(
                opId = stored.opId,
                op = PersistedPrimitiveOp.fromDomain(stored.op),
                phaseId = stored.phaseId,
            )
    }
}

@Serializable
data class PersistedBlueprintDraft(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val blueprintId: String,
    val origin: PersistedVec3i,
    val facing: String = CardinalDirection.NORTH.name,
    val nextOpNumber: Int = 1,
    val ops: List<PersistedBlueprintOp> = emptyList(),
    val placement: PersistedPlacementState = PersistedPlacementState(),
    val phases: List<PersistedPhase> = emptyList(),
    val phaseView: PersistedPhaseViewState? = null,
) {
    fun parsedFacing(): CardinalDirection = runCatching {
        CardinalDirection.valueOf(facing.trim().uppercase())
    }.getOrElse {
        throw IllegalArgumentException("Invalid facing '$facing' in persisted draft '$blueprintId'.")
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/**
 * Atomic JSON sidecar persistence store for blueprint draft sessions (`<blueprint_id>.draft.json`).
 */
class BlueprintDraftStore(
    val draftsDir: Path,
) {
    private val logger = LoggerFactory.getLogger(BlueprintDraftStore::class.java)
    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun draftFileFor(blueprintId: String): Path {
        BlueprintSessionStore.requireValidBlueprintId(blueprintId)
        return draftsDir.resolve("$blueprintId$DRAFT_EXTENSION")
    }

    fun save(draft: PersistedBlueprintDraft) {
        BlueprintSessionStore.requireValidBlueprintId(draft.blueprintId)
        require(draft.schemaVersion == PersistedBlueprintDraft.CURRENT_SCHEMA_VERSION) {
            "Unsupported draft schemaVersion '${draft.schemaVersion}'."
        }
        require(draft.nextOpNumber >= 1) {
            "Draft nextOpNumber must be >= 1, got ${draft.nextOpNumber}."
        }
        Files.createDirectories(draftsDir)
        val targetFile = draftFileFor(draft.blueprintId)
        val payload = json.encodeToString(draft) + "\n"
        writeAtomically(targetFile, draft.blueprintId, payload)
    }

    fun loadAll(
        onWarning: (String, Throwable?) -> Unit = { message, error ->
            if (error != null) logger.warn(message, error) else logger.warn(message)
        },
    ): List<PersistedBlueprintDraft> {
        if (!Files.isDirectory(draftsDir)) {
            return emptyList()
        }
        val files = Files.list(draftsDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(DRAFT_EXTENSION) }
                .sorted()
                .toList()
        }
        val loaded = ArrayList<PersistedBlueprintDraft>(files.size)
        for (file in files) {
            val fileName = file.fileName.toString()
            val expectedId = fileName.removeSuffix(DRAFT_EXTENSION)
            try {
                val raw = Files.readString(file, StandardCharsets.UTF_8)
                val draft = json.decodeFromString<PersistedBlueprintDraft>(raw)
                validateLoadedDraft(draft, expectedId)
                loaded.add(draft)
            } catch (t: Throwable) {
                onWarning(
                    "Skipping invalid or corrupted draft file '$file': ${t.message ?: t::class.simpleName}",
                    t,
                )
            }
        }
        return loaded
    }

    private fun validateLoadedDraft(draft: PersistedBlueprintDraft, expectedId: String) {
        BlueprintSessionStore.requireValidBlueprintId(draft.blueprintId)
        require(draft.blueprintId == expectedId) {
            "Draft blueprintId '${draft.blueprintId}' does not match filename stem '$expectedId'."
        }
        require(draft.schemaVersion == PersistedBlueprintDraft.CURRENT_SCHEMA_VERSION) {
            "Unsupported draft schemaVersion '${draft.schemaVersion}'."
        }
        require(draft.nextOpNumber >= 1) {
            "Invalid nextOpNumber '${draft.nextOpNumber}'."
        }
        draft.parsedFacing()
        val seenPhaseIds = HashSet<String>(draft.phases.size)
        for (phase in draft.phases) {
            val domainPhase = phase.toDomain()
            require(seenPhaseIds.add(domainPhase.phaseId)) {
                "Duplicate phaseId '${domainPhase.phaseId}' in draft '${draft.blueprintId}'."
            }
        }
        draft.phaseView?.toDomain()
        val seenIds = HashSet<String>(draft.ops.size)
        for (entry in draft.ops) {
            val domain = entry.toDomain()
            require(seenIds.add(domain.opId)) {
                "Duplicate opId '${domain.opId}' in draft '${draft.blueprintId}'."
            }
            if (domain.phaseId != null) {
                require(domain.phaseId in seenPhaseIds) {
                    "Operation '${domain.opId}' references unknown phaseId '${domain.phaseId}' in draft '${draft.blueprintId}'."
                }
            }
        }
    }

    private fun writeAtomically(targetFile: Path, blueprintId: String, payload: String) {
        val tempFile = Files.createTempFile(draftsDir, "$blueprintId-", "$DRAFT_EXTENSION.tmp")
        try {
            Files.writeString(tempFile, payload, StandardCharsets.UTF_8)
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

    companion object {
        const val DRAFT_EXTENSION = ".draft.json"
    }
}
