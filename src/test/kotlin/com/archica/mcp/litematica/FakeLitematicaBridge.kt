package com.archica.mcp.litematica

import com.archica.mcp.context.BlockPos3i
import com.archica.mcp.context.ContextCompressionException
import com.archica.mcp.context.ContextErrorCode
import java.nio.file.Path

/** Pure-Kotlin fake [LitematicaBridge] for unit and MCP integration testing. */
class FakeLitematicaBridge(
    private val schematicsDir: Path,
) : LitematicaBridge {
    data class PlacementRecord(
        val blueprintId: String,
        val schematicFile: Path,
        val worldOrigin: BlockPos3i,
        var visibleRegions: Set<String>? = null,
    )

    var worldLoaded: Boolean = true
    var adapterFailureMessage: String? = null
    val activePlacements = LinkedHashMap<String, PlacementRecord>()
    val evictedFiles = mutableListOf<Path>()
    var activateCalls: Int = 0
    var visibilityCalls: Int = 0
    var clearCalls: Int = 0
    var listCalls: Int = 0

    override fun schematicsDirectory(): Path = schematicsDir

    override suspend fun activatePlacement(
        blueprintId: String,
        schematicFile: Path,
        worldOrigin: BlockPos3i,
        visibleRegions: Set<String>?,
    ): Boolean {
        activateCalls++
        if (!worldLoaded) {
            throw ContextCompressionException(
                ContextErrorCode.NO_WORLD_LOADED,
                "Minecraft client is not currently in a world (title screen or disconnecting).",
            )
        }
        adapterFailureMessage?.let { msg ->
            throw LitematicaUnavailableException(msg)
        }
        val replaced = activePlacements.containsKey(blueprintId)
        activePlacements[blueprintId] = PlacementRecord(blueprintId, schematicFile, worldOrigin, visibleRegions)
        return replaced
    }

    override suspend fun setSubRegionVisibility(
        blueprintId: String,
        visibleRegions: Set<String>,
    ): Boolean {
        visibilityCalls++
        if (!worldLoaded) return false
        adapterFailureMessage?.let { msg ->
            throw LitematicaUnavailableException(msg)
        }
        val existing = activePlacements[blueprintId] ?: return false
        existing.visibleRegions = visibleRegions
        return true
    }

    override suspend fun clearPlacement(blueprintId: String): Boolean {
        clearCalls++
        if (!worldLoaded) {
            throw ContextCompressionException(
                ContextErrorCode.NO_WORLD_LOADED,
                "Minecraft client is not currently in a world (title screen or disconnecting).",
            )
        }
        adapterFailureMessage?.let { msg ->
            throw LitematicaUnavailableException(msg)
        }
        return activePlacements.remove(blueprintId) != null
    }

    override suspend fun evictCachedSchematic(schematicFile: Path) {
        evictedFiles.add(schematicFile)
    }

    override suspend fun listActivePlacements(): Set<String> {
        listCalls++
        if (!worldLoaded) return emptySet()
        return activePlacements.keys.toSet()
    }
}
