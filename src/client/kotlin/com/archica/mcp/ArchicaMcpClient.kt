package com.archica.mcp

import com.archica.mcp.context.BuildContextRequest
import com.archica.mcp.context.BuildContextResult
import com.archica.mcp.context.ClientThreadDispatcher
import com.archica.mcp.context.ScanVolumeRequest
import com.archica.mcp.context.ScanVolumeResult
import com.archica.mcp.server.ArchicaEmbeddedMcpServer
import com.archica.mcp.server.McpPortConfig
import com.archica.mcp.server.SpatialContextService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory

/** Fabric client entrypoint for Archica MCP. */
object ArchicaMcpClient : ClientModInitializer {
    private val logger = LoggerFactory.getLogger(ArchicaModInfo.MOD_ID)
    private val lifecycleLock = Any()

    @Volatile
    private var serverScope: CoroutineScope? = null

    @Volatile
    private var mcpServer: ArchicaEmbeddedMcpServer? = null

    @Volatile
    private var stoppingHookRegistered: Boolean = false

    override fun onInitializeClient() {
        val port = McpPortConfig.resolvePort()
        val version = FabricLoader.getInstance()
            .getModContainer(ArchicaModInfo.MOD_ID)
            .map { it.metadata.version.friendlyString }
            .orElse("0.1.0")

        val dispatcher = ClientThreadDispatcher()
        val spatialService = object : SpatialContextService {
            override suspend fun getBuildContext(request: BuildContextRequest): BuildContextResult =
                dispatcher.getBuildContext(request)

            override suspend fun scanVolume(request: ScanVolumeRequest): ScanVolumeResult =
                dispatcher.scanVolume(request)
        }

        val server = ArchicaEmbeddedMcpServer(
            spatialContext = spatialService,
            port = port,
            serverVersion = version,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        synchronized(lifecycleLock) {
            mcpServer = server
            serverScope = scope
            if (!stoppingHookRegistered) {
                ClientLifecycleEvents.CLIENT_STOPPING.register {
                    stopServer()
                }
                stoppingHookRegistered = true
            }
        }

        scope.launch {
            try {
                server.start()
            } catch (t: Throwable) {
                logger.warn(
                    "{} unexpected error during async MCP startup on {}: {}",
                    ArchicaModInfo.MOD_NAME,
                    server.endpointUrl,
                    t.message ?: t::class.simpleName,
                    t,
                )
            }
        }
    }

    private fun stopServer() {
        val serverToStop: ArchicaEmbeddedMcpServer?
        val scopeToCancel: CoroutineScope?
        synchronized(lifecycleLock) {
            serverToStop = mcpServer
            scopeToCancel = serverScope
            mcpServer = null
            serverScope = null
        }
        serverToStop?.stop()
        scopeToCancel?.cancel()
    }
}
