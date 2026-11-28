package com.archica.mcp.blueprint

import com.archica.mcp.context.BlockPos3i
import com.archica.mcp.context.CardinalDirection
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlueprintDraftStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `persists and reloads all 11 primitive variants losslessly`() {
        val draftsDir = tempDir.resolve("schematics/archica")
        val store = BlueprintDraftStore(draftsDir)

        val allOps = listOf(
            StoredBlueprintOp("op_1", PrimitiveOp.SetBlock(BlockPos3i(0, 0, 0), "minecraft:stone")),
            StoredBlueprintOp("op_2", PrimitiveOp.FillBox(BlockPos3i(1, 0, 1), BlockPos3i(3, 2, 3), "minecraft:oak_planks")),
            StoredBlueprintOp("op_3", PrimitiveOp.HollowBox(BlockPos3i(0, 0, 0), BlockPos3i(4, 3, 4), "minecraft:cobblestone", wallsOnly = true)),
            StoredBlueprintOp("op_4", PrimitiveOp.Line(BlockPos3i(0, 1, 0), BlockPos3i(4, 1, 0), "minecraft:oak_log")),
            StoredBlueprintOp("op_5", PrimitiveOp.Cylinder(BlockPos3i(5, 0, 5), radius = 2, height = 4, block = "minecraft:stone_bricks", hollow = true)),
            StoredBlueprintOp("op_6", PrimitiveOp.Dome(BlockPos3i(5, 4, 5), radius = 3, block = "minecraft:glass", hollow = true)),
            StoredBlueprintOp(
                "op_7",
                PrimitiveOp.GableRoof(
                    from = BlockPos3i(0, 4, 0),
                    to = BlockPos3i(4, 4, 6),
                    block = "minecraft:oak_stairs",
                    ridgeBlock = "minecraft:oak_slab",
                    ridgeAxis = RidgeAxis.Z,
                    overhang = 1,
                ),
            ),
            StoredBlueprintOp("op_8", PrimitiveOp.Staircase(BlockPos3i(1, 0, 0), BlockPos3i(1, 3, 3), "minecraft:oak_stairs", width = 2, fillUnder = true)),
            StoredBlueprintOp("op_9", PrimitiveOp.ReplaceBox(BlockPos3i(0, 0, 0), BlockPos3i(4, 2, 4), "minecraft:cobblestone", "minecraft:mossy_cobblestone")),
            StoredBlueprintOp("op_10", PrimitiveOp.ClearBox(BlockPos3i(2, 1, 0), BlockPos3i(2, 2, 0))),
            StoredBlueprintOp(
                "op_11",
                PrimitiveOp.RepeatPattern(
                    op = PrimitiveOp.SetBlock(BlockPos3i(0, 0, 0), "minecraft:oak_fence"),
                    count = 4,
                    step = BlockPos3i(2, 0, 0),
                ),
            ),
        )

        val draft = PersistedBlueprintDraft(
            blueprintId = "full_villa",
            origin = PersistedVec3i(100, 64, -50),
            facing = CardinalDirection.EAST.name,
            nextOpNumber = 12,
            ops = allOps.map { PersistedBlueprintOp.fromDomain(it) },
            placement = PersistedPlacementState(
                activated = true,
                lastWorldOrigin = PersistedVec3i(99, 64, -50),
                lastCompiledAt = 123456789L,
            ),
        )

        store.save(draft)
        val draftFile = store.draftFileFor("full_villa")
        assertTrue(Files.exists(draftFile))
        assertFalse(Files.list(draftsDir).use { it.anyMatch { p -> p.fileName.toString().endsWith(".tmp") } })

        val reloaded = store.loadAll()
        assertEquals(1, reloaded.size)
        val loadedDraft = reloaded.single()
        assertEquals(draft, loadedDraft)
        assertEquals(CardinalDirection.EAST, loadedDraft.parsedFacing())
        assertEquals(allOps, loadedDraft.ops.map { it.toDomain() })
    }

    @Test
    fun `loadAll skips malformed, mismatched, and invalid-version files with warnings`() {
        val draftsDir = tempDir.resolve("drafts")
        val store = BlueprintDraftStore(draftsDir)

        store.save(
            PersistedBlueprintDraft(
                blueprintId = "valid_hut",
                origin = PersistedVec3i(10, 64, 10),
                facing = CardinalDirection.NORTH.name,
                nextOpNumber = 2,
                ops = listOf(PersistedBlueprintOp.fromDomain(StoredBlueprintOp("op_1", PrimitiveOp.SetBlock(BlockPos3i(0, 0, 0), "minecraft:stone")))),
            ),
        )

        Files.writeString(draftsDir.resolve("broken.draft.json"), "{ not valid json", StandardCharsets.UTF_8)
        Files.writeString(
            draftsDir.resolve("mismatch.draft.json"),
            """{"schemaVersion":1,"blueprintId":"other_id","origin":{"x":0,"y":64,"z":0},"facing":"NORTH","nextOpNumber":1,"ops":[]}""",
            StandardCharsets.UTF_8,
        )
        Files.writeString(
            draftsDir.resolve("future.draft.json"),
            """{"schemaVersion":99,"blueprintId":"future","origin":{"x":0,"y":64,"z":0},"facing":"NORTH","nextOpNumber":1,"ops":[]}""",
            StandardCharsets.UTF_8,
        )

        val warnings = mutableListOf<String>()
        val loaded = store.loadAll { msg, _ -> warnings.add(msg) }

        assertEquals(1, loaded.size)
        assertEquals("valid_hut", loaded.single().blueprintId)
        assertEquals(3, warnings.size)
    }

    @Test
    fun `persists phases, op phaseId, and phaseView cursor and reloads legacy drafts cleanly`() {
        val draftsDir = tempDir.resolve("phased_drafts")
        val store = BlueprintDraftStore(draftsDir)

        val draft = PersistedBlueprintDraft(
            blueprintId = "phased_hut",
            origin = PersistedVec3i(0, 64, 0),
            facing = CardinalDirection.NORTH.name,
            nextOpNumber = 3,
            ops = listOf(
                PersistedBlueprintOp("op_1", PersistedPrimitiveOp.SetBlock(PersistedVec3i(0, 0, 0), "minecraft:cobblestone"), phaseId = "foundation"),
                PersistedBlueprintOp("op_2", PersistedPrimitiveOp.SetBlock(PersistedVec3i(0, 1, 0), "minecraft:oak_planks"), phaseId = "walls"),
            ),
            phases = listOf(
                PersistedPhase(phaseId = "foundation", order = 1, title = "Foundation", description = "Stone base"),
                PersistedPhase(phaseId = "walls", order = 2, title = "Walls"),
            ),
            phaseView = PersistedPhaseViewState(activePhaseId = "walls", viewMode = PhaseViewMode.CUMULATIVE.name),
        )
        store.save(draft)

        // Legacy v1 draft JSON without phases/phaseView fields
        Files.writeString(
            draftsDir.resolve("legacy_hut.draft.json"),
            """{"schemaVersion":1,"blueprintId":"legacy_hut","origin":{"x":1,"y":64,"z":2},"facing":"NORTH","nextOpNumber":2,"ops":[{"opId":"op_1","op":{"type":"set_block","pos":{"x":0,"y":0,"z":0},"block":"minecraft:stone"}}]}""",
            StandardCharsets.UTF_8,
        )

        val loaded = store.loadAll().associateBy { it.blueprintId }
        assertEquals(2, loaded.size)
        val phased = loaded.getValue("phased_hut")
        assertEquals(2, phased.phases.size)
        assertEquals("walls", phased.phaseView?.activePhaseId)
        assertEquals(PhaseViewMode.CUMULATIVE.name, phased.phaseView?.viewMode)

        val legacy = loaded.getValue("legacy_hut")
        assertTrue(legacy.phases.isEmpty())
        assertEquals(null, legacy.phaseView)
    }
}
