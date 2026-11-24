package com.archica.mcp.litematica

import com.archica.mcp.context.BlockPos3i
import com.archica.mcp.context.ClientThreadDispatcher
import fi.dy.masa.litematica.data.DataManager
import fi.dy.masa.litematica.data.SchematicHolder
import fi.dy.masa.litematica.schematic.LitematicaSchematic
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement
import net.minecraft.core.BlockPos
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Minecraft `26.3` / Litematica `0.29.1` implementation of [LitematicaBridge] marshalled onto the client main thread.
 */
class ClientLitematicaBridge(
    private val dispatcher: ClientThreadDispatcher,
) : LitematicaBridge {
    private val activePlacementIds = ConcurrentHashMap.newKeySet<String>()

    override fun schematicsDirectory(): Path = try {
        DataManager.getSchematicsBaseDirectory()
    } catch (t: Throwable) {
        throw LitematicaUnavailableException(
            "Failed to resolve Litematica schematics directory: ${t.message ?: t::class.simpleName}",
            cause = t,
        )
    }

    override suspend fun activatePlacement(
        blueprintId: String,
        schematicFile: Path,
        worldOrigin: BlockPos3i,
    ): Boolean = dispatcher.runOnClientThread {
        try {
            val placementManager = DataManager.getSchematicPlacementManager()
            val schematicHolder = SchematicHolder.getInstance()
            val targetName = placementName(blueprintId)

            val existingPlacements = placementManager.getAllSchematicsPlacements()
                .filter { it.name == targetName }
            for (existing in existingPlacements) {
                placementManager.removeSchematicPlacement(existing)
            }

            // Evict any cached schematic pointing at the overwritten path so Litematica reloads from disk.
            val normalizedFile = schematicFile.toAbsolutePath().normalize()
            val staleSchematics = schematicHolder.allSchematics
                .filter { it.file?.toAbsolutePath()?.normalize() == normalizedFile }
            for (stale in staleSchematics) {
                schematicHolder.removeSchematic(stale)
            }

            val dir = schematicFile.parent
            val fileName = schematicFile.fileName.toString()
            val schematic = LitematicaSchematic.createFromFile(dir, fileName)
                ?: throw LitematicaUnavailableException(
                    "Litematica failed to load compiled schematic '$fileName' from '$dir'.",
                )
            schematicHolder.addSchematic(schematic, false)

            val placement = SchematicPlacement.createFor(
                schematic,
                BlockPos(worldOrigin.x, worldOrigin.y, worldOrigin.z),
                targetName,
                true,
                true,
            )
            placementManager.addSchematicPlacement(placement, false)
            activePlacementIds.add(blueprintId)
            existingPlacements.isNotEmpty()
        } catch (e: LitematicaUnavailableException) {
            throw e
        } catch (t: Throwable) {
            throw LitematicaUnavailableException(
                "Litematica failed to activate placement for '$blueprintId': ${t.message ?: t::class.simpleName}",
                cause = t,
            )
        }
    }

    override suspend fun clearPlacement(blueprintId: String): Boolean = dispatcher.runOnClientThread {
        try {
            val placementManager = DataManager.getSchematicPlacementManager()
            val targetName = placementName(blueprintId)
            val existingPlacements = placementManager.getAllSchematicsPlacements()
                .filter { it.name == targetName }
            for (existing in existingPlacements) {
                placementManager.removeSchematicPlacement(existing)
            }
            activePlacementIds.remove(blueprintId)
            existingPlacements.isNotEmpty()
        } catch (e: LitematicaUnavailableException) {
            throw e
        } catch (t: Throwable) {
            throw LitematicaUnavailableException(
                "Litematica failed to clear placement for '$blueprintId': ${t.message ?: t::class.simpleName}",
                cause = t,
            )
        }
    }

    override fun hasActivePlacement(blueprintId: String): Boolean {
        val targetName = placementName(blueprintId)
        return runCatching {
            DataManager.getSchematicPlacementManager()
                .getAllSchematicsPlacements()
                .any { it.name == targetName }
        }.getOrElse {
            activePlacementIds.contains(blueprintId)
        }
    }

    companion object {
        fun placementName(blueprintId: String): String = "archica:$blueprintId"
    }
}
