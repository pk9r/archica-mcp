package com.archica.mcp.litematica

import com.archica.mcp.context.BlockPos3i
import com.archica.mcp.context.ClientThreadDispatcher
import com.archica.mcp.context.ContextCompressionException
import com.archica.mcp.context.ContextErrorCode
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
            evictMatchingSchematics(schematicHolder, schematicFile)

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

    override suspend fun evictCachedSchematic(schematicFile: Path) {
        if (!dispatcher.isWorldLoaded()) return
        try {
            dispatcher.runOnClientThread {
                evictMatchingSchematics(SchematicHolder.getInstance(), schematicFile)
            }
        } catch (e: ContextCompressionException) {
            if (e.code != ContextErrorCode.NO_WORLD_LOADED) throw e
        }
    }

    override suspend fun listActivePlacements(): Set<String> {
        if (!dispatcher.isWorldLoaded()) return emptySet()
        return try {
            dispatcher.runOnClientThread {
                val active = DataManager.getSchematicPlacementManager()
                    .getAllSchematicsPlacements()
                    .mapNotNull { placement ->
                        placement.name
                            .takeIf { it.startsWith(PLACEMENT_PREFIX) }
                            ?.removePrefix(PLACEMENT_PREFIX)
                    }
                    .toSet()
                activePlacementIds.clear()
                activePlacementIds.addAll(active)
                active
            }
        } catch (e: ContextCompressionException) {
            if (e.code == ContextErrorCode.NO_WORLD_LOADED) emptySet() else throw e
        }
    }

    private fun evictMatchingSchematics(schematicHolder: SchematicHolder, schematicFile: Path) {
        val normalizedFile = schematicFile.toAbsolutePath().normalize()
        val staleSchematics = schematicHolder.allSchematics
            .filter { it.file?.toAbsolutePath()?.normalize() == normalizedFile }
        for (stale in staleSchematics) {
            schematicHolder.removeSchematic(stale)
        }
    }

    companion object {
        const val PLACEMENT_PREFIX = "archica:"

        fun placementName(blueprintId: String): String = "$PLACEMENT_PREFIX$blueprintId"
    }
}
