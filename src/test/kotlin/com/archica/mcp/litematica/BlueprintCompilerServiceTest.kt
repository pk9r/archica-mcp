package com.archica.mcp.litematica

import com.archica.mcp.blueprint.BlueprintClearPlacementRequest
import com.archica.mcp.blueprint.BlueprintCompileRequest
import com.archica.mcp.blueprint.BlueprintCreateRequest
import com.archica.mcp.blueprint.BlueprintService
import com.archica.mcp.blueprint.FakeBlockCatalog
import com.archica.mcp.blueprint.PrimitiveOp
import com.archica.mcp.blueprint.SurvivalSnapshotProvider
import com.archica.mcp.context.BlockPos3i
import com.archica.mcp.context.CardinalDirection
import com.archica.mcp.context.FakeWorldContextSource
import com.archica.mcp.context.InventoryEntry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlueprintCompilerServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `compile supports activate=false off-world and activates, replaces, and clears when world is loaded`() = runBlocking {
        val world = FakeWorldContextSource(
            loaded = false,
            inventory = listOf(InventoryEntry("minecraft:oak_planks", 5)),
        )
        val schematicsDir = tempDir.resolve("schematics")
        val bridge = FakeLitematicaBridge(schematicsDir).apply { worldLoaded = false }
        val feedbackEvents = mutableListOf<com.archica.mcp.blueprint.PlacementFeedbackEvent>()
        val service = BlueprintService(
            catalog = FakeBlockCatalog(),
            survivalProvider = SurvivalSnapshotProvider.fromWorldSource(world),
            litematicaBridge = bridge,
            placementFeedbackListener = feedbackEvents::add,
        )

        service.create(BlueprintCreateRequest("cottage", BlockPos3i(10, 64, 10), CardinalDirection.NORTH))
        service.addOps("cottage", listOf(PrimitiveOp.FillBox(BlockPos3i(0, 0, 0), BlockPos3i(2, 0, 2), "minecraft:oak_planks")))

        // Title screen compile with activate = false succeeds without touching world
        val offlineCompile = service.compile(BlueprintCompileRequest("cottage", activate = false))
        assertFalse(offlineCompile.activated)
        assertFalse(offlineCompile.replacedExistingPlacement)
        assertNull(offlineCompile.survivalCheck)
        assertEquals("schematics/archica/cottage.litematic", offlineCompile.schematicPath)
        assertTrue(Files.exists(schematicsDir.resolve("archica/cottage.litematic")))
        assertFalse(service.list().blueprints.single().hasActivePlacement)

        // World loads -> activate = true registers placement and updates blueprint_list
        world.loaded = true
        bridge.worldLoaded = true
        val firstActive = service.compile(BlueprintCompileRequest("cottage", activate = true))
        assertTrue(firstActive.activated)
        assertFalse(firstActive.replacedExistingPlacement)
        assertEquals(4, firstActive.survivalCheck!!.missingMaterials["minecraft:oak_planks"])
        assertTrue(service.list().blueprints.single().hasActivePlacement)

        // Second compile replaces existing placement cleanly
        val recompile = service.compile(BlueprintCompileRequest("cottage", activate = true))
        assertTrue(recompile.activated)
        assertTrue(recompile.replacedExistingPlacement)
        assertEquals(1, bridge.activePlacements.size)

        // Clear without delete_file preserves .litematic on disk
        val clearedKeep = service.clearPlacement(BlueprintClearPlacementRequest("cottage", deleteFile = false))
        assertTrue(clearedKeep.cleared)
        assertFalse(clearedKeep.deletedFile)
        assertFalse(service.list().blueprints.single().hasActivePlacement)
        assertTrue(Files.exists(schematicsDir.resolve("archica/cottage.litematic")))

        // Clear again reports cleared=false, delete_file=true evicts cache and deletes file
        val clearedDelete = service.clearPlacement(BlueprintClearPlacementRequest("cottage", deleteFile = true))
        assertFalse(clearedDelete.cleared)
        assertTrue(clearedDelete.deletedFile)
        assertEquals(listOf(schematicsDir.resolve("archica/cottage.litematic")), bridge.evictedFiles)
        assertFalse(Files.exists(schematicsDir.resolve("archica/cottage.litematic")))
        assertTrue(bridge.listCalls >= 3)
        assertEquals(3, feedbackEvents.size)
        assertTrue(feedbackEvents[0] is com.archica.mcp.blueprint.PlacementFeedbackEvent.Activated)
        assertTrue((feedbackEvents[1] as com.archica.mcp.blueprint.PlacementFeedbackEvent.Activated).replacedExisting)
        assertTrue(feedbackEvents[2] is com.archica.mcp.blueprint.PlacementFeedbackEvent.Cleared)
    }

    @Test
    fun `adapter failure preserves schematic_path on LitematicaUnavailableException`() = runBlocking {
        val schematicsDir = tempDir.resolve("schematics")
        val bridge = FakeLitematicaBridge(schematicsDir).apply {
            adapterFailureMessage = "Litematica PlacementManager failed"
        }
        val service = BlueprintService(catalog = FakeBlockCatalog(), litematicaBridge = bridge)
        service.create(BlueprintCreateRequest("barn", BlockPos3i(0, 64, 0)))
        service.addOps("barn", listOf(PrimitiveOp.SetBlock(BlockPos3i(0, 0, 0), "minecraft:stone")))

        val ex = assertFailsWith<LitematicaUnavailableException> {
            service.compile(BlueprintCompileRequest("barn", activate = true))
        }
        assertEquals("schematics/archica/barn.litematic", ex.schematicPath)
        assertTrue(ex.message.contains("schematics/archica/barn.litematic"))
        assertTrue(Files.exists(schematicsDir.resolve("archica/barn.litematic")))
    }
}
