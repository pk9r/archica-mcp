package com.archica.mcp.command

import com.archica.mcp.blueprint.BlueprintPhaseNavigationResult
import com.archica.mcp.blueprint.BlueprintService
import com.archica.mcp.blueprint.PhaseViewMode
import com.archica.mcp.context.ContextCompressionException
import com.archica.mcp.litematica.LitematicaBridge
import com.archica.mcp.litematica.LitematicaUnavailableException
import com.archica.mcp.server.ArchicaMcpServerController
import com.archica.mcp.ui.ArchicaPhaseScreen
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Registers client-side `/archica` commands for inspecting status, controlling the embedded MCP server,
 * toggling opt-in player inventory tracking, navigating build phases, and clearing active Archica placements.
 */
object ArchicaClientCommands {
    fun register(
        controller: ArchicaMcpServerController,
        blueprintService: BlueprintService,
        litematicaBridge: LitematicaBridge,
        scope: CoroutineScope,
        inventoryEnabled: AtomicBoolean = AtomicBoolean(false),
        selectedPhasedBlueprintId: AtomicReference<String?> = AtomicReference(null),
        onOpenConfigScreen: (() -> Unit)? = null,
    ) {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            val root = ClientCommands.literal("archica")
            if (onOpenConfigScreen != null) {
                root.executes {
                    onOpenConfigScreen()
                    1
                }
            }
            dispatcher.register(
                root
                    .then(
                        ClientCommands.literal("status")
                            .executes { ctx ->
                                val client = ctx.source.client
                                scope.launch {
                                    val status = controller.status()
                                    val drafts = blueprintService.sessionCount()
                                    val activePlacements = runCatching {
                                        litematicaBridge.listActivePlacements().sorted()
                                    }.getOrElse { emptyList() }
                                    val stateLabel = if (status.running) "running at ${status.endpointUrl}" else "stopped (configured: ${status.endpointUrl})"
                                    val inventoryLabel = if (inventoryEnabled.get()) "on" else "off"
                                    val placementsSummary = if (activePlacements.isEmpty()) {
                                        "none"
                                    } else {
                                        "${activePlacements.size} (${activePlacements.joinToString(", ")})"
                                    }
                                    postFeedback(
                                        client,
                                        "[Archica] MCP: $stateLabel | inventory: $inventoryLabel | drafts: $drafts | active placements: $placementsSummary",
                                        if (status.running) ChatFormatting.GREEN else ChatFormatting.YELLOW,
                                    )
                                }
                                1
                            },
                    )
                    .then(
                        ClientCommands.literal("inventory")
                            .executes { ctx ->
                                val enabled = inventoryEnabled.get()
                                val msg = if (enabled) {
                                    "[Archica] Inventory tracking is ON (get_build_context and survival_check include player inventory)."
                                } else {
                                    "[Archica] Inventory tracking is OFF (player inventory is ignored; resets to off on restart)."
                                }
                                postFeedback(
                                    ctx.source.client,
                                    msg,
                                    if (enabled) ChatFormatting.GREEN else ChatFormatting.GRAY,
                                )
                                1
                            }
                            .then(
                                ClientCommands.literal("on")
                                    .executes { ctx ->
                                        inventoryEnabled.set(true)
                                        postFeedback(
                                            ctx.source.client,
                                            "[Archica] Inventory tracking enabled for this session (resets to off on restart).",
                                            ChatFormatting.GREEN,
                                        )
                                        1
                                    },
                            )
                            .then(
                                ClientCommands.literal("off")
                                    .executes { ctx ->
                                        inventoryEnabled.set(false)
                                        postFeedback(
                                            ctx.source.client,
                                            "[Archica] Inventory tracking disabled (player inventory will be ignored).",
                                            ChatFormatting.YELLOW,
                                        )
                                        1
                                    },
                            ),
                    )
                    .then(
                        ClientCommands.literal("mcp")
                            .then(
                                ClientCommands.literal("start")
                                    .executes { ctx ->
                                        handleStart(ctx.source.client, controller, scope, portOverride = null)
                                        1
                                    }
                                    .then(
                                        ClientCommands.argument("port", IntegerArgumentType.integer())
                                            .executes { ctx ->
                                                val port = IntegerArgumentType.getInteger(ctx, "port")
                                                handleStart(ctx.source.client, controller, scope, portOverride = port)
                                                1
                                            },
                                    ),
                            )
                            .then(
                                ClientCommands.literal("stop")
                                    .executes { ctx ->
                                        val client = ctx.source.client
                                        scope.launch {
                                            val stopped = controller.stop()
                                            if (stopped) {
                                                postFeedback(client, "[Archica] Embedded MCP server stopped.", ChatFormatting.YELLOW)
                                            } else {
                                                postFeedback(client, "[Archica] Embedded MCP server is already stopped.", ChatFormatting.GRAY)
                                            }
                                        }
                                        1
                                    },
                            ),
                    )
                    .then(
                        ClientCommands.literal("clear")
                            .then(
                                ClientCommands.argument("target", StringArgumentType.word())
                                    .suggests { _, builder ->
                                        val candidates = buildList {
                                            add("all")
                                            addAll(blueprintService.sessionIds())
                                        }
                                        SharedSuggestionProvider.suggest(candidates, builder)
                                    }
                                    .executes { ctx ->
                                        val target = StringArgumentType.getString(ctx, "target")
                                        handleClear(ctx.source.client, litematicaBridge, scope, target)
                                        1
                                    },
                            ),
                    )
                    .then(
                        ClientCommands.literal("phase")
                            .executes { ctx ->
                                handlePhaseList(ctx.source.client, blueprintService, scope, selectedPhasedBlueprintId)
                                1
                            }
                            .then(
                                ClientCommands.literal("list")
                                    .executes { ctx ->
                                        handlePhaseList(ctx.source.client, blueprintService, scope, selectedPhasedBlueprintId)
                                        1
                                    },
                            )
                            .then(
                                ClientCommands.literal("next")
                                    .executes { ctx ->
                                        handlePhaseAction(ctx.source.client, blueprintService, scope, selectedPhasedBlueprintId) { id ->
                                            blueprintService.stepPhase(id, 1)
                                        }
                                        1
                                    },
                            )
                            .then(
                                ClientCommands.literal("prev")
                                    .executes { ctx ->
                                        handlePhaseAction(ctx.source.client, blueprintService, scope, selectedPhasedBlueprintId) { id ->
                                            blueprintService.stepPhase(id, -1)
                                        }
                                        1
                                    },
                            )
                            .then(
                                ClientCommands.literal("select")
                                    .then(
                                        ClientCommands.argument("blueprint_id", StringArgumentType.word())
                                            .suggests { _, builder ->
                                                SharedSuggestionProvider.suggest(blueprintService.sessionIds(), builder)
                                            }
                                            .executes { ctx ->
                                                val bpId = StringArgumentType.getString(ctx, "blueprint_id")
                                                handlePhaseSelect(ctx.source.client, blueprintService, scope, selectedPhasedBlueprintId, bpId)
                                                1
                                            },
                                    ),
                            )
                            .then(
                                ClientCommands.literal("mode")
                                    .executes { ctx ->
                                        handlePhaseAction(ctx.source.client, blueprintService, scope, selectedPhasedBlueprintId) { id ->
                                            blueprintService.togglePhaseViewMode(id)
                                        }
                                        1
                                    }
                                    .then(
                                        ClientCommands.argument("mode", StringArgumentType.word())
                                            .suggests { _, builder ->
                                                SharedSuggestionProvider.suggest(listOf("isolated", "cumulative", "all"), builder)
                                            }
                                            .executes { ctx ->
                                                val rawMode = StringArgumentType.getString(ctx, "mode")
                                                handlePhaseMode(ctx.source.client, blueprintService, scope, selectedPhasedBlueprintId, rawMode)
                                                1
                                            },
                                    ),
                            )
                            .then(
                                ClientCommands.argument("target", StringArgumentType.word())
                                    .executes { ctx ->
                                        val target = StringArgumentType.getString(ctx, "target")
                                        handlePhaseAction(ctx.source.client, blueprintService, scope, selectedPhasedBlueprintId) { id ->
                                            blueprintService.setActivePhase(id, target)
                                        }
                                        1
                                    },
                            ),
                    ),
            )
        }
    }

    private fun handleStart(
        client: Minecraft,
        controller: ArchicaMcpServerController,
        scope: CoroutineScope,
        portOverride: Int?,
    ) {
        scope.launch {
            when (val outcome = controller.start(portOverride)) {
                is ArchicaMcpServerController.StartOutcome.Started -> {
                    val verb = if (outcome.restarted) "Restarted" else "Started"
                    postFeedback(
                        client,
                        "[Archica] $verb MCP server at ${outcome.status.endpointUrl}",
                        ChatFormatting.GREEN,
                    )
                }
                is ArchicaMcpServerController.StartOutcome.AlreadyRunning -> {
                    postFeedback(
                        client,
                        "[Archica] MCP server is already running at ${outcome.status.endpointUrl}",
                        ChatFormatting.AQUA,
                    )
                }
                is ArchicaMcpServerController.StartOutcome.InvalidPort -> {
                    postFeedback(
                        client,
                        "[Archica] Invalid port ${outcome.requestedPort}: expected integer 1..65535.",
                        ChatFormatting.RED,
                    )
                }
                is ArchicaMcpServerController.StartOutcome.BindFailed -> {
                    postFeedback(
                        client,
                        "[Archica] Failed to bind MCP server at ${outcome.status.endpointUrl} (port may be in use).",
                        ChatFormatting.RED,
                    )
                }
            }
        }
    }

    private fun handleClear(
        client: Minecraft,
        litematicaBridge: LitematicaBridge,
        scope: CoroutineScope,
        target: String,
    ) {
        scope.launch {
            try {
                if (target.equals("all", ignoreCase = true)) {
                    val activeIds = litematicaBridge.listActivePlacements().sorted()
                    if (activeIds.isEmpty()) {
                        postFeedback(client, "[Archica] No active Archica placements found.", ChatFormatting.GRAY)
                        return@launch
                    }
                    val cleared = mutableListOf<String>()
                    for (id in activeIds) {
                        if (litematicaBridge.clearPlacement(id)) {
                            cleared.add(id)
                        }
                    }
                    postFeedback(
                        client,
                        "[Archica] Cleared ${cleared.size} active placement(s): ${cleared.joinToString(", ")} (.litematic files kept).",
                        ChatFormatting.AQUA,
                    )
                } else {
                    val removed = litematicaBridge.clearPlacement(target)
                    if (removed) {
                        postFeedback(
                            client,
                            "[Archica] Cleared placement '$target' (.litematic file kept).",
                            ChatFormatting.AQUA,
                        )
                    } else {
                        postFeedback(
                            client,
                            "[Archica] No active placement found for '$target'.",
                            ChatFormatting.GRAY,
                        )
                    }
                }
            } catch (e: ContextCompressionException) {
                postFeedback(client, "[Archica] ${e.message}", ChatFormatting.RED)
            } catch (e: LitematicaUnavailableException) {
                postFeedback(client, "[Archica] ${e.message}", ChatFormatting.RED)
            } catch (t: Throwable) {
                postFeedback(
                    client,
                    "[Archica] Failed to clear placement '$target': ${t.message ?: t::class.simpleName}",
                    ChatFormatting.RED,
                )
            }
        }
    }

    private suspend fun resolvePhasedBlueprintId(
        blueprintService: BlueprintService,
        selectedPhasedBlueprintId: AtomicReference<String?>,
    ): String? {
        val chosen = blueprintService.resolvePhasedBlueprintId(selectedPhasedBlueprintId.get())
        if (chosen != null) {
            selectedPhasedBlueprintId.set(chosen)
        }
        return chosen
    }

    private fun handlePhaseList(
        client: Minecraft,
        blueprintService: BlueprintService,
        scope: CoroutineScope,
        selectedPhasedBlueprintId: AtomicReference<String?>,
    ) {
        scope.launch {
            val targetId = resolvePhasedBlueprintId(blueprintService, selectedPhasedBlueprintId)
            if (targetId == null) {
                postFeedback(client, "[Archica] Active blueprints have no build phases.", ChatFormatting.YELLOW)
                return@launch
            }
            runCatching { blueprintService.getPhaseOverview(targetId) }
                .onSuccess { overview ->
                    val modeLabel = ArchicaPhaseScreen.formatModeLabel(overview.phaseView.viewMode)
                    postFeedback(
                        client,
                        "[Archica] Phases for '${overview.blueprintId}' (Mode: $modeLabel):",
                        ChatFormatting.AQUA,
                    )
                    for (phase in overview.phases) {
                        val isActive = phase.phaseId == overview.phaseView.activePhaseId
                        val prefix = if (isActive) "* " else "  "
                        val desc = phase.description?.let { " - $it" }.orEmpty()
                        postFeedback(
                            client,
                            "$prefix${phase.order}. ${phase.title} [${phase.phaseId}] (${phase.totalBlocks} blocks)$desc",
                            if (isActive) ChatFormatting.GREEN else ChatFormatting.GRAY,
                        )
                    }
                }
                .onFailure { err ->
                    postFeedback(client, "[Archica] ${err.message ?: err::class.simpleName}", ChatFormatting.RED)
                }
        }
    }

    private fun handlePhaseSelect(
        client: Minecraft,
        blueprintService: BlueprintService,
        scope: CoroutineScope,
        selectedPhasedBlueprintId: AtomicReference<String?>,
        blueprintId: String,
    ) {
        scope.launch {
            runCatching { blueprintService.getPhaseOverview(blueprintId) }
                .onSuccess { overview ->
                    if (overview.phases.isEmpty()) {
                        postFeedback(client, "[Archica] Blueprint '$blueprintId' has no build phases.", ChatFormatting.YELLOW)
                    } else {
                        selectedPhasedBlueprintId.set(blueprintId)
                        postFeedback(
                            client,
                            "[Archica] Selected phased blueprint '$blueprintId' (${overview.phases.size} phases).",
                            ChatFormatting.GREEN,
                        )
                    }
                }
                .onFailure { err ->
                    postFeedback(client, "[Archica] ${err.message ?: err::class.simpleName}", ChatFormatting.RED)
                }
        }
    }

    private fun handlePhaseMode(
        client: Minecraft,
        blueprintService: BlueprintService,
        scope: CoroutineScope,
        selectedPhasedBlueprintId: AtomicReference<String?>,
        rawMode: String,
    ) {
        val parsedMode = runCatching { PhaseViewMode.parse(rawMode) }.getOrNull()
        if (parsedMode == null) {
            postFeedback(client, "[Archica] Invalid mode '$rawMode': expected isolated, cumulative, or all.", ChatFormatting.RED)
            return
        }
        handlePhaseAction(client, blueprintService, scope, selectedPhasedBlueprintId) { id ->
            blueprintService.setPhaseViewMode(id, parsedMode)
        }
    }

    private fun handlePhaseAction(
        client: Minecraft,
        blueprintService: BlueprintService,
        scope: CoroutineScope,
        selectedPhasedBlueprintId: AtomicReference<String?>,
        action: suspend (String) -> BlueprintPhaseNavigationResult,
    ) {
        scope.launch {
            val targetId = resolvePhasedBlueprintId(blueprintService, selectedPhasedBlueprintId)
            if (targetId == null) {
                postFeedback(client, "[Archica] Active blueprints have no build phases.", ChatFormatting.YELLOW)
                return@launch
            }
            runCatching { action(targetId) }
                .onSuccess { nav ->
                    val summary = "[Archica] [${nav.blueprintId}] Phase ${nav.activePhaseIndex}/${nav.totalPhases}: " +
                        "${nav.activePhase.title} | ${ArchicaPhaseScreen.formatModeLabel(nav.viewMode)} (${nav.activePhase.totalBlocks} blocks)"
                    client.execute {
                        client.player?.sendOverlayMessage(ArchicaPhaseScreen.formatActionBar(nav))
                        client.player?.sendSystemMessage(Component.literal(summary).withStyle(ChatFormatting.AQUA))
                    }
                }
                .onFailure { err ->
                    postFeedback(client, "[Archica] ${err.message ?: err::class.simpleName}", ChatFormatting.RED)
                }
        }
    }

    private fun postFeedback(client: Minecraft, message: String, color: ChatFormatting) {
        client.execute {
            client.player?.sendSystemMessage(Component.literal(message).withStyle(color))
        }
    }
}
