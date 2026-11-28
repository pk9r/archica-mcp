package com.archica.mcp

import com.archica.mcp.blueprint.BlueprintDraftStore
import com.archica.mcp.blueprint.BlueprintPhaseNavigationResult
import com.archica.mcp.blueprint.BlueprintService
import com.archica.mcp.blueprint.ClientBlockCatalog
import com.archica.mcp.blueprint.PlacementFeedbackEvent
import com.archica.mcp.blueprint.SurvivalSnapshotProvider
import com.archica.mcp.command.ArchicaClientCommands
import com.archica.mcp.config.ArchicaClientConfig
import com.archica.mcp.config.ArchicaConfigStore
import com.archica.mcp.context.BuildContextRequest
import com.archica.mcp.context.BuildContextResult
import com.archica.mcp.context.ClientThreadDispatcher
import com.archica.mcp.context.ScanVolumeRequest
import com.archica.mcp.context.ScanVolumeResult
import com.archica.mcp.litematica.ClientLitematicaBridge
import com.archica.mcp.litematica.LitematicaBridge
import com.archica.mcp.litematica.LitematicSchematicCompiler
import com.archica.mcp.server.ArchicaMcpServerController
import com.archica.mcp.server.McpPortConfig
import com.archica.mcp.server.SpatialContextService
import com.archica.mcp.ui.ArchicaConfigScreen
import com.archica.mcp.ui.ArchicaPhaseScreen
import com.mojang.blaze3d.platform.InputConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.SharedConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Shared live client services and configuration state exposed to commands, keybinds, and the config screen. */
class ArchicaClientRuntime(
    val configStore: ArchicaConfigStore,
    val configRef: AtomicReference<ArchicaClientConfig>,
    val controller: ArchicaMcpServerController,
    val blueprintService: BlueprintService,
    val litematicaBridge: LitematicaBridge,
    val scope: CoroutineScope,
    val inventoryEnabled: AtomicBoolean,
    val selectedPhasedBlueprintId: AtomicReference<String?> = AtomicReference(null),
) {
    /** Resolves the preferred active phased blueprint (or latest phased draft), updating [selectedPhasedBlueprintId]. */
    suspend fun resolveTargetPhasedBlueprintId(): String? {
        val chosen = blueprintService.resolvePhasedBlueprintId(selectedPhasedBlueprintId.get())
        if (chosen != null) {
            selectedPhasedBlueprintId.set(chosen)
        }
        return chosen
    }
}

/** Fabric client entrypoint for Archica MCP. */
object ArchicaMcpClient : ClientModInitializer {
    private val logger = LoggerFactory.getLogger(ArchicaModInfo.MOD_ID)
    private val lifecycleLock = Any()
    private val pendingOpenConfigScreen = AtomicBoolean(false)

    @Volatile
    private var clientRuntime: ArchicaClientRuntime? = null

    @Volatile
    private var hooksRegistered: Boolean = false

    @Volatile
    private var openConfigKeyMapping: KeyMapping? = null

    override fun onInitializeClient() {
        val configPath = FabricLoader.getInstance().configDir.resolve("${ArchicaModInfo.MOD_ID}.json")
        val configStore = ArchicaConfigStore(configPath)
        val initialConfig = configStore.load()
        val configRef = AtomicReference(initialConfig)

        val port = McpPortConfig.resolvePort(fallbackPort = initialConfig.mcpPort)
        val version = FabricLoader.getInstance()
            .getModContainer(ArchicaModInfo.MOD_ID)
            .map { it.metadata.version.friendlyString }
            .orElse("0.1.0")

        // Privacy opt-in always resets to OFF on every client launch.
        val inventoryEnabled = AtomicBoolean(false)
        val dispatcher = ClientThreadDispatcher()
        val spatialService = object : SpatialContextService {
            override suspend fun getBuildContext(request: BuildContextRequest): BuildContextResult =
                dispatcher.getBuildContext(request.copy(includeInventory = inventoryEnabled.get()))

            override suspend fun scanVolume(request: ScanVolumeRequest): ScanVolumeResult =
                dispatcher.scanVolume(request)
        }

        val litematicaBridge = ClientLitematicaBridge(dispatcher)
        val schematicCompiler = LitematicSchematicCompiler(
            minecraftDataVersionProvider = { SharedConstants.getCurrentVersion().dataVersion().version() },
        )
        val draftsDir = runCatching {
            litematicaBridge.schematicsDirectory().resolve(LitematicSchematicCompiler.ARCHICA_SUBDIR)
        }.getOrElse {
            FabricLoader.getInstance().gameDir
                .resolve("schematics")
                .resolve(LitematicSchematicCompiler.ARCHICA_SUBDIR)
        }
        val draftStore = BlueprintDraftStore(draftsDir)
        val blueprintService = BlueprintService(
            catalog = ClientBlockCatalog(),
            survivalProvider = SurvivalSnapshotProvider {
                if (!inventoryEnabled.get()) null else dispatcher.readSurvivalSnapshotOrNull()
            },
            litematicaBridge = litematicaBridge,
            compiler = schematicCompiler,
            draftStore = draftStore,
            placementFeedbackListener = { event -> notifyPlacementFeedback(event) },
        )
        val restoredDraftCount = blueprintService.loadPersistedDrafts()
        if (restoredDraftCount > 0) {
            logger.info(
                "{} restored {} persisted blueprint draft(s) from {}",
                ArchicaModInfo.MOD_NAME,
                restoredDraftCount,
                draftsDir,
            )
        }

        val controller = ArchicaMcpServerController(
            spatialContext = spatialService,
            blueprintService = blueprintService,
            initialPort = port,
            serverVersion = version,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val runtime = ArchicaClientRuntime(
            configStore = configStore,
            configRef = configRef,
            controller = controller,
            blueprintService = blueprintService,
            litematicaBridge = litematicaBridge,
            scope = scope,
            inventoryEnabled = inventoryEnabled,
        )

        synchronized(lifecycleLock) {
            clientRuntime = runtime
            if (!hooksRegistered) {
                registerKeybindAndTickHandlers()
                ClientLifecycleEvents.CLIENT_STOPPING.register {
                    stopServer()
                }
                hooksRegistered = true
            }
        }

        ArchicaClientCommands.register(
            controller = controller,
            blueprintService = blueprintService,
            litematicaBridge = litematicaBridge,
            scope = scope,
            inventoryEnabled = inventoryEnabled,
            selectedPhasedBlueprintId = runtime.selectedPhasedBlueprintId,
            onOpenConfigScreen = { requestOpenConfigScreen() },
        )

        if (initialConfig.autoStartMcp) {
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
        } else {
            logger.info(
                "{} auto-start is disabled in config; embedded MCP server remains stopped until started manually.",
                ArchicaModInfo.MOD_NAME,
            )
        }
    }

    fun runtimeOrNull(): ArchicaClientRuntime? = clientRuntime

    fun createConfigScreen(parent: Screen?): Screen {
        val runtime = clientRuntime
            ?: error("${ArchicaModInfo.MOD_NAME} client runtime has not been initialized yet.")
        return ArchicaConfigScreen(parent, runtime)
    }

    fun requestOpenConfigScreen() {
        pendingOpenConfigScreen.set(true)
    }

    private fun registerKeybindAndTickHandlers() {
        val category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ArchicaModInfo.MOD_ID, "general"))
        val keyMapping = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "key.archica-mcp.open_config",
                InputConstants.Type.KEYBOARD,
                InputConstants.UNKNOWN.value,
                category,
            ),
        )
        val nextPhaseKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "key.archica-mcp.phase_next",
                InputConstants.Type.KEYBOARD,
                InputConstants.UNKNOWN.value,
                category,
            ),
        )
        val prevPhaseKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "key.archica-mcp.phase_prev",
                InputConstants.Type.KEYBOARD,
                InputConstants.UNKNOWN.value,
                category,
            ),
        )
        val togglePhaseModeKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "key.archica-mcp.phase_toggle_mode",
                InputConstants.Type.KEYBOARD,
                InputConstants.UNKNOWN.value,
                category,
            ),
        )
        openConfigKeyMapping = keyMapping

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val runtime = clientRuntime ?: return@register
            if (pendingOpenConfigScreen.compareAndSet(true, false)) {
                client.gui.setScreen(ArchicaConfigScreen(client.gui.screen(), runtime))
                return@register
            }
            while (keyMapping.consumeClick()) {
                if (client.gui.screen() == null) {
                    client.gui.setScreen(ArchicaConfigScreen(null, runtime))
                }
            }
            while (nextPhaseKey.consumeClick()) {
                if (client.gui.screen() == null) {
                    triggerPhaseKeyAction(client, runtime) { id -> runtime.blueprintService.stepPhase(id, 1) }
                }
            }
            while (prevPhaseKey.consumeClick()) {
                if (client.gui.screen() == null) {
                    triggerPhaseKeyAction(client, runtime) { id -> runtime.blueprintService.stepPhase(id, -1) }
                }
            }
            while (togglePhaseModeKey.consumeClick()) {
                if (client.gui.screen() == null) {
                    triggerPhaseKeyAction(client, runtime) { id -> runtime.blueprintService.togglePhaseViewMode(id) }
                }
            }
        }
    }

    private fun triggerPhaseKeyAction(
        client: Minecraft,
        runtime: ArchicaClientRuntime,
        action: suspend (String) -> BlueprintPhaseNavigationResult,
    ) {
        runtime.scope.launch {
            val targetId = runtime.resolveTargetPhasedBlueprintId()
            if (targetId == null) {
                client.execute {
                    client.player?.sendOverlayMessage(
                        Component.literal("[Archica] No phased blueprints available.").withStyle(ChatFormatting.YELLOW),
                    )
                }
                return@launch
            }
            runCatching { action(targetId) }
                .onSuccess { nav ->
                    client.execute {
                        client.player?.sendOverlayMessage(ArchicaPhaseScreen.formatActionBar(nav))
                    }
                }
                .onFailure { err ->
                    client.execute {
                        client.player?.sendOverlayMessage(
                            Component.literal("[Archica] ${err.message ?: err::class.simpleName}")
                                .withStyle(ChatFormatting.RED),
                        )
                    }
                }
        }
    }

    private fun notifyPlacementFeedback(event: PlacementFeedbackEvent) {
        if (event is PlacementFeedbackEvent.Activated) {
            clientRuntime?.selectedPhasedBlueprintId?.set(event.blueprintId)
        }
        if (clientRuntime?.configRef?.get()?.showPlacementFeedback == false) return
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
        val runtimeToStop: ArchicaClientRuntime?
        synchronized(lifecycleLock) {
            runtimeToStop = clientRuntime
            clientRuntime = null
        }
        runtimeToStop?.controller?.stop()
        runtimeToStop?.scope?.cancel()
    }
}
