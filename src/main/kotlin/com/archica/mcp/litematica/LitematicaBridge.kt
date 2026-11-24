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
     * replacing any existing placement for [blueprintId]. Returns `true` if a prior placement was replaced.
     */
    suspend fun activatePlacement(
        blueprintId: String,
        schematicFile: Path,
        worldOrigin: BlockPos3i,
    ): Boolean

    /** Removes any active placement for [blueprintId]; returns `true` if at least one placement was removed. */
    suspend fun clearPlacement(blueprintId: String): Boolean

    /** Returns `true` if [blueprintId] currently has an active placement managed by Archica. */
    fun hasActivePlacement(blueprintId: String): Boolean
}
