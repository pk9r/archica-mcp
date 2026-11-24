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
    )

    var worldLoaded: Boolean = true
    var adapterFailureMessage: String? = null
    val activePlacements = LinkedHashMap<String, PlacementRecord>()
    var activateCalls: Int = 0
    var clearCalls: Int = 0

    override fun schematicsDirectory(): Path = schematicsDir

    override suspend fun activatePlacement(
        blueprintId: String,
        schematicFile: Path,
        worldOrigin: BlockPos3i,
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
        activePlacements[blueprintId] = PlacementRecord(blueprintId, schematicFile, worldOrigin)
        return replaced
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

    override fun hasActivePlacement(blueprintId: String): Boolean = activePlacements.containsKey(blueprintId)
}
