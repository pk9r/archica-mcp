package com.archica.mcp.blueprint

import com.archica.mcp.context.BlockPos3i
import com.archica.mcp.context.CardinalDirection
import com.archica.mcp.context.FakeWorldContextSource
import com.archica.mcp.context.InventoryEntry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BlueprintSessionStoreTest {
    private val catalog = FakeBlockCatalog()

    @Test
    fun `create enforces unique id unless overwrite is true`() {
        val store = BlueprintSessionStore(catalog)
        val created = store.create(BlueprintCreateRequest("cottage", BlockPos3i(10, 64, 10), CardinalDirection.EAST))
        assertEquals(0, created.opCount)

        val dup = assertFailsWith<BlueprintException> {
            store.create(BlueprintCreateRequest("cottage", BlockPos3i(0, 64, 0)))
        }
        assertEquals(BlueprintErrorCode.BLUEPRINT_EXISTS, dup.code)

        store.create(BlueprintCreateRequest("cottage", BlockPos3i(0, 70, 0), overwrite = true))
        assertEquals(BlockPos3i(0, 70, 0), store.inspect("cottage").origin)
    }

    @Test
    fun `op ids increase monotonically and in-place update preserves pipeline order`() {
        val store = BlueprintSessionStore(catalog)
        store.create(BlueprintCreateRequest("hut", BlockPos3i(0, 64, 0)))

        val added = store.addOps(
            "hut",
            listOf(
                PrimitiveOp.FillBox(BlockPos3i(0, 0, 0), BlockPos3i(2, 0, 2), "minecraft:cobblestone"),
                PrimitiveOp.SetBlock(BlockPos3i(1, 0, 1), "minecraft:oak_planks"),
                PrimitiveOp.SetBlock(BlockPos3i(0, 1, 0), "minecraft:oak_log"),
            ),
        )
        assertEquals(listOf("op_1", "op_2", "op_3"), added.addedOpIds)

        store.removeOp("hut", "op_1")
        val afterAdd = store.addOps("hut", listOf(PrimitiveOp.SetBlock(BlockPos3i(2, 1, 2), "minecraft:glass")))
        assertEquals(listOf("op_4"), afterAdd.addedOpIds)

        val updated = store.updateOp("hut", "op_2", PrimitiveOp.SetBlock(BlockPos3i(1, 0, 1), "minecraft:stone_bricks"))
        assertEquals("op_2", updated.updatedOpId)
        assertEquals(listOf("op_2", "op_3", "op_4"), updated.ops.map { it.opId })
        assertEquals("minecraft:stone_bricks", updated.grid.get(BlockPos3i(1, 0, 1))?.blockId)
    }

    @Test
    fun `invalid batch does not mutate session and undo clamps to available ops`() {
        val store = BlueprintSessionStore(catalog)
        store.create(BlueprintCreateRequest("tower", BlockPos3i(0, 64, 0)))
        store.addOps("tower", listOf(PrimitiveOp.SetBlock(BlockPos3i(0, 0, 0), "minecraft:stone")))

        val invalid = assertFailsWith<BlueprintException> {
            store.addOps(
                "tower",
                listOf(
                    PrimitiveOp.SetBlock(BlockPos3i(0, 1, 0), "minecraft:cobblestone"),
                    PrimitiveOp.SetBlock(BlockPos3i(0, 2, 0), "minecraft:fake_block"),
                ),
            )
        }
        assertEquals(BlueprintErrorCode.INVALID_BLOCK_ID, invalid.code)
        assertEquals(1, store.inspect("tower").ops.size)

        val undone = store.undo("tower", count = 5)
        assertEquals(listOf("op_1"), undone.removedOpIds)
        assertEquals(0, undone.ops.size)
        assertEquals(0, undone.grid.totalBlocks)
    }

    @Test
    fun `missing blueprint and missing op raise expected error codes`() {
        val store = BlueprintSessionStore(catalog)
        store.create(BlueprintCreateRequest("valid", BlockPos3i(0, 64, 0)))

        val missingBp = assertFailsWith<BlueprintException> { store.inspect("nope") }
        assertEquals(BlueprintErrorCode.BLUEPRINT_NOT_FOUND, missingBp.code)

        val missingOp = assertFailsWith<BlueprintException> { store.removeOp("valid", "op_99") }
        assertEquals(BlueprintErrorCode.OP_NOT_FOUND, missingOp.code)
    }

    @Test
    fun `service computes survival missing materials non-blockingly and omits when creative or unloaded`() = runBlocking {
        val world = FakeWorldContextSource(
            inventory = listOf(InventoryEntry("minecraft:oak_planks", 5)),
        )
        val service = BlueprintService(
            catalog = catalog,
            survivalProvider = SurvivalSnapshotProvider.fromWorldSource(world),
            activePlacementChecker = { it == "cabin" },
        )
        service.create(BlueprintCreateRequest("cabin", BlockPos3i(10, 64, 10), CardinalDirection.EAST))

        val result = service.addOps(
            "cabin",
            listOf(PrimitiveOp.FillBox(BlockPos3i(0, 0, 0), BlockPos3i(2, 0, 2), "minecraft:oak_planks")),
        )
        assertEquals(9, result.totalBlocks)
        assertNotNull(result.survivalCheck)
        assertEquals(4, result.survivalCheck.missingMaterials["minecraft:oak_planks"])

        val inspected = service.inspect("cabin", includeOps = false)
        assertNull(inspected.ops)
        assertEquals(BlueprintBounds(BlockPos3i(10, 64, 10), BlockPos3i(12, 64, 12)), inspected.worldBoundingBox)

        val list = service.list()
        assertEquals(1, list.blueprints.size)
        assertEquals(true, list.blueprints.first().hasActivePlacement)

        world.loaded = false
        val unloadedInspect = service.inspect("cabin")
        assertNull(unloadedInspect.survivalCheck)

        world.loaded = true
        world.player = world.player.copy(gameMode = "creative")
        val creativeInspect = service.inspect("cabin")
        assertNull(creativeInspect.survivalCheck)

        // When inventory tracking is disabled (provider returns null), survivalCheck is omitted even in survival mode
        world.player = world.player.copy(gameMode = "survival")
        val disabledService = BlueprintService(
            catalog = catalog,
            survivalProvider = SurvivalSnapshotProvider { null },
        )
        disabledService.create(BlueprintCreateRequest("cabin_no_inv", BlockPos3i(0, 64, 0)))
        val disabledAdd = disabledService.addOps(
            "cabin_no_inv",
            listOf(PrimitiveOp.FillBox(BlockPos3i(0, 0, 0), BlockPos3i(2, 0, 2), "minecraft:oak_planks")),
        )
        assertNull(disabledAdd.survivalCheck)
        assertNull(disabledService.inspect("cabin_no_inv").survivalCheck)
    }

    @Test
    fun `persists across store reload, clamps nextOpNumber, and skips drafts with unknown blocks`(
        @TempDir tempDir: Path,
    ) {
        val draftsDir = tempDir.resolve("schematics/archica")
        val draftStore = BlueprintDraftStore(draftsDir)
        val firstStore = BlueprintSessionStore(catalog = catalog, draftStore = draftStore)

        firstStore.create(BlueprintCreateRequest("bridge", BlockPos3i(20, 64, 30), CardinalDirection.SOUTH))
        firstStore.addOps(
            "bridge",
            listOf(
                PrimitiveOp.SetBlock(BlockPos3i(0, 0, 0), "minecraft:stone"),
                PrimitiveOp.SetBlock(BlockPos3i(1, 0, 0), "minecraft:oak_planks"),
            ),
        )
        firstStore.removeOp("bridge", "op_1")

        // Write a second draft with an unknown block ID and stale nextOpNumber on a valid third draft
        Files.writeString(
            draftsDir.resolve("bad_block.draft.json"),
            """
            {
              "schemaVersion": 1,
              "blueprintId": "bad_block",
              "origin": {"x": 0, "y": 64, "z": 0},
              "facing": "NORTH",
              "nextOpNumber": 2,
              "ops": [{"opId": "op_1", "op": {"type": "set_block", "pos": {"x": 0, "y": 0, "z": 0}, "block": "minecraft:does_not_exist"}}]
            }
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )
        Files.writeString(
            draftsDir.resolve("clamped.draft.json"),
            """
            {
              "schemaVersion": 1,
              "blueprintId": "clamped",
              "origin": {"x": 5, "y": 64, "z": 5},
              "facing": "NORTH",
              "nextOpNumber": 1,
              "ops": [{"opId": "op_4", "op": {"type": "set_block", "pos": {"x": 0, "y": 0, "z": 0}, "block": "minecraft:stone"}}]
            }
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )

        val reloadedStore = BlueprintSessionStore(catalog = catalog, draftStore = draftStore)
        val loadedCount = reloadedStore.loadPersistedDrafts()
        assertEquals(2, loadedCount)
        assertEquals(listOf("bridge", "clamped"), reloadedStore.sessionIds())

        val nextBridge = reloadedStore.addOps("bridge", listOf(PrimitiveOp.SetBlock(BlockPos3i(2, 0, 0), "minecraft:glass")))
        assertEquals(listOf("op_3"), nextBridge.addedOpIds)
        assertEquals(listOf("op_2", "op_3"), nextBridge.ops.map { it.opId })

        val nextClamped = reloadedStore.addOps("clamped", listOf(PrimitiveOp.SetBlock(BlockPos3i(1, 0, 0), "minecraft:glass")))
        assertEquals(listOf("op_5"), nextClamped.addedOpIds)
    }

    @Test
    fun `semantic phases order deterministically, prune empty phases, preserve cursor, and compute per-phase survival`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val world = FakeWorldContextSource(
            inventory = listOf(InventoryEntry("minecraft:cobblestone", 12)),
        )
        val draftStore = BlueprintDraftStore(tempDir.resolve("drafts"))
        val service = BlueprintService(
            catalog = catalog,
            survivalProvider = SurvivalSnapshotProvider.fromWorldSource(world),
            draftStore = draftStore,
        )
        service.create(BlueprintCreateRequest("cottage", BlockPos3i(0, 64, 0)))

        service.addOps(
            "cottage",
            listOf(PrimitiveOp.FillBox(BlockPos3i(0, 0, 0), BlockPos3i(2, 0, 2), "minecraft:cobblestone")),
            batchPhase = BlueprintPhaseInput(title = "Foundation & Floor", description = "Stone base"),
        )
        service.addOps(
            "cottage",
            listOf(PrimitiveOp.SetBlock(BlockPos3i(1, 1, 1), "minecraft:stone")),
            batchPhase = BlueprintPhaseInput(phaseId = "scaffold", title = "Temporary Scaffold"),
        )
        service.addOps(
            "cottage",
            listOf(
                PrimitiveOp.ClearBox(BlockPos3i(1, 1, 1), BlockPos3i(1, 1, 1)),
                PrimitiveOp.FillBox(BlockPos3i(0, 1, 0), BlockPos3i(0, 3, 0), "minecraft:oak_log"),
            ),
            batchPhase = BlueprintPhaseInput(title = "Structural Frame"),
        )

        val inspected = service.inspect("cottage")
        // Empty "scaffold" phase was pruned after ClearBox
        assertEquals(listOf("foundation_floor", "structural_frame"), inspected.phases.map { it.phaseId })
        assertEquals(listOf(1, 2), inspected.phases.map { it.order })
        assertEquals("foundation_floor", inspected.phaseView?.activePhaseId)
        assertEquals(PhaseViewMode.ISOLATED, inspected.phaseView?.viewMode)

        // Phase 1 needs 9 cobblestone and player has 12 -> 0 missing; Phase 2 needs 3 oak_log -> 3 missing
        val p1 = inspected.phases[0]
        val p2 = inspected.phases[1]
        assertEquals(0, p1.survivalCheck?.missingMaterials?.size)
        assertEquals(3, p2.survivalCheck?.missingMaterials?.get("minecraft:oak_log"))

        // Reload from disk preserves phases and cursor
        val reloaded = BlueprintService(catalog = catalog, draftStore = draftStore)
        assertEquals(1, reloaded.loadPersistedDrafts())
        val afterReload = reloaded.inspect("cottage")
        assertEquals(listOf("foundation_floor", "structural_frame"), afterReload.phases.map { it.phaseId })
        assertEquals("foundation_floor", afterReload.phaseView?.activePhaseId)
        assertEquals(PhaseViewMode.ISOLATED, afterReload.phaseView?.viewMode)
    }
}
