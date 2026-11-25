package com.archica.mcp.context

import com.archica.mcp.blueprint.SurvivalInventorySnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import net.minecraft.client.Minecraft

/**
 * Marshals world context scans safely onto the Minecraft client main thread with a bounded timeout.
 */
class ClientThreadDispatcher(
    private val client: Minecraft = Minecraft.getInstance(),
    private val defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    fun isWorldLoaded(): Boolean = client.level != null && client.player != null

    suspend fun getBuildContext(request: BuildContextRequest = BuildContextRequest()): BuildContextResult =
        runOnClientThread {
            BuildContextScanner(ClientWorldContextSource(client)).scan(request)
        }

    suspend fun scanVolume(request: ScanVolumeRequest): ScanVolumeResult =
        runOnClientThread {
            VolumeScanner(ClientWorldContextSource(client)).scan(request)
        }

    /** Reads player game mode and inventory on the client thread, or returns null when no world is loaded. */
    suspend fun readSurvivalSnapshotOrNull(): SurvivalInventorySnapshot? {
        if (client.level == null || client.player == null) return null
        return runCatching {
            runOnClientThread {
                val source = ClientWorldContextSource(client)
                SurvivalInventorySnapshot(
                    gameMode = source.getPlayerSnapshot().gameMode,
                    inventory = source.getInventoryMaterials(),
                )
            }
        }.getOrNull()
    }

    suspend fun <T> runOnClientThread(timeoutMs: Long = defaultTimeoutMs, block: () -> T): T {
        if (client.isSameThread) {
            ensureWorldLoaded()
            return block()
        }

        val deferred = CompletableDeferred<T>()
        client.execute {
            try {
                ensureWorldLoaded()
                deferred.complete(block())
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
            }
        }
        return withTimeout(timeoutMs) {
            deferred.await()
        }
    }

    private fun ensureWorldLoaded() {
        if (client.level == null || client.player == null) {
            throw ContextCompressionException(
                ContextErrorCode.NO_WORLD_LOADED,
                "Minecraft client is not currently in a world (title screen or disconnecting).",
            )
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 5_000L
    }
}
