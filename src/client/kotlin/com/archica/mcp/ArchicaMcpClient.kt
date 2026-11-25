package com.archica.mcp

import com.archica.mcp.blueprint.BlueprintService
import com.archica.mcp.blueprint.ClientBlockCatalog
import com.archica.mcp.blueprint.PlacementFeedbackEvent
import com.archica.mcp.blueprint.SurvivalSnapshotProvider
import com.archica.mcp.command.ArchicaClientCommands
import com.archica.mcp.context.BuildContextRequest
import com.archica.mcp.context.BuildContextResult
import com.archica.mcp.context.ClientThreadDispatcher
import com.archica.mcp.context.ScanVolumeRequest
import com.archica.mcp.context.ScanVolumeResult
import com.archica.mcp.litematica.ClientLitematicaBridge
import com.archica.mcp.litematica.LitematicSchematicCompiler
import com.archica.mcp.server.ArchicaMcpServerController
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
import net.minecraft.ChatFormatting
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

/** Fabric client entrypoint for Archica MCP. */
object ArchicaMcpClient : ClientModInitializer {
    private val logger = LoggerFactory.getLogger(ArchicaModInfo.MOD_ID)
    private val lifecycleLock = Any()

    @Volatile
    private var serverScope: CoroutineScope? = null

    @Volatile
    private var serverController: ArchicaMcpServerController? = null

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

        val litematicaBridge = ClientLitematicaBridge(dispatcher)
        val schematicCompiler = LitematicSchematicCompiler(
            minecraftDataVersionProvider = { SharedConstants.getCurrentVersion().dataVersion().version() },
        )
        val blueprintService = BlueprintService(
            catalog = ClientBlockCatalog(),
            survivalProvider = SurvivalSnapshotProvider { dispatcher.readSurvivalSnapshotOrNull() },
            litematicaBridge = litematicaBridge,
            compiler = schematicCompiler,
            placementFeedbackListener = { event -> notifyPlacementFeedback(event) },
        )

        val controller = ArchicaMcpServerController(
            spatialContext = spatialService,
            blueprintService = blueprintService,
            initialPort = port,
            serverVersion = version,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        synchronized(lifecycleLock) {
            serverController = controller
            serverScope = scope
            if (!stoppingHookRegistered) {
                ClientLifecycleEvents.CLIENT_STOPPING.register {
                    stopServer()
                }
                stoppingHookRegistered = true
            }
        }

        ArchicaClientCommands.register(
            controller = controller,
            blueprintService = blueprintService,
            litematicaBridge = litematicaBridge,
            scope = scope,
        )

        scope.launch {
            try {
                controller.start()
            } catch (t: Throwable) {
                logger.warn(
                    "{} unexpected error during async MCP startup on {}: {}",
                    ArchicaModInfo.MOD_NAME,
                    controller.status().endpointUrl,
                    t.message ?: t::class.simpleName,
                    t,
                )
            }
        }
    }

    private fun notifyPlacementFeedback(event: PlacementFeedbackEvent) {
        val text = when (event) {
            is PlacementFeedbackEvent.Activated -> {
                val verb = if (event.replacedExisting) "Updated" else "Activated"
                "[Archica] $verb '${event.blueprintId}' (${event.totalBlocks} blocks) at ${event.worldOrigin.formatTuple()}"
            }
            is PlacementFeedbackEvent.Cleared -> {
                val suffix = if (event.deletedFile) " and deleted .litematic file" else ""
                "[Archica] Cleared '${event.blueprintId}' placement$suffix"
            }
        }
        val client = Minecraft.getInstance()
        client.execute {
            client.player?.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.AQUA))
        }
    }

    private fun stopServer() {
        val controllerToStop: ArchicaMcpServerController?
        val scopeToCancel: CoroutineScope?
        synchronized(lifecycleLock) {
            controllerToStop = serverController
            scopeToCancel = serverScope
            serverController = null
            serverScope = null
        }
        controllerToStop?.stop()
        scopeToCancel?.cancel()
    }
}
