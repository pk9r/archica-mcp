package com.archica.mcp.context

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
    suspend fun getBuildContext(request: BuildContextRequest = BuildContextRequest()): BuildContextResult =
        runOnClientThread {
            BuildContextScanner(ClientWorldContextSource(client)).scan(request)
        }

    suspend fun scanVolume(request: ScanVolumeRequest): ScanVolumeResult =
        runOnClientThread {
            VolumeScanner(ClientWorldContextSource(client)).scan(request)
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
