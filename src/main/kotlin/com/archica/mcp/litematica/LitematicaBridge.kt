package com.archica.mcp.litematica

import com.archica.mcp.context.BlockPos3i
import java.nio.file.Path

/** Thrown when Litematica or MaLiLib fails while loading, activating, or clearing a schematic placement. */
class LitematicaUnavailableException(
    override val message: String,
    val schematicPath: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Port abstracting Litematica directory resolution and client-thread hologram placement lifecycle.
 */
interface LitematicaBridge {
    fun schematicsDirectory(): Path

    /**
     * Loads [schematicFile] and activates an `"archica:<blueprintId>"` placement at [worldOrigin],
     * replacing any existing placement for [blueprintId]. When [visibleRegions] is non-null and non-empty,
     * only matching subregions are enabled on activation. Returns `true` if a prior placement was replaced.
     */
    suspend fun activatePlacement(
        blueprintId: String,
        schematicFile: Path,
        worldOrigin: BlockPos3i,
        visibleRegions: Set<String>? = null,
    ): Boolean

    /**
     * Updates subregion visibility on an active `"archica:<blueprintId>"` placement in place without
     * rewriting the `.litematic` file. Returns `true` if a matching active placement was updated.
     */
    suspend fun setSubRegionVisibility(
        blueprintId: String,
        visibleRegions: Set<String>,
    ): Boolean = false

    /** Removes any active placement for [blueprintId]; returns `true` if at least one placement was removed. */
    suspend fun clearPlacement(blueprintId: String): Boolean

    /** Evicts any cached schematic matching [schematicFile] before deleting it from disk. */
    suspend fun evictCachedSchematic(schematicFile: Path) {}

    /** Returns the set of `blueprintId`s that currently have an active placement managed by Archica. */
    suspend fun listActivePlacements(): Set<String>
}
