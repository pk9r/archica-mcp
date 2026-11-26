package com.archica.mcp.command

import com.archica.mcp.blueprint.BlueprintService
import com.archica.mcp.context.ContextCompressionException
import com.archica.mcp.litematica.LitematicaBridge
import com.archica.mcp.litematica.LitematicaUnavailableException
import com.archica.mcp.server.ArchicaMcpServerController
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

/**
 * Registers client-side `/archica` commands for inspecting status, controlling the embedded MCP server,
 * toggling opt-in player inventory tracking, and clearing active Archica Litematica placements directly from chat.
 */
object ArchicaClientCommands {
    fun register(
        controller: ArchicaMcpServerController,
        blueprintService: BlueprintService,
        litematicaBridge: LitematicaBridge,
        scope: CoroutineScope,
        inventoryEnabled: AtomicBoolean = AtomicBoolean(false),
    ) {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommands.literal("archica")
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

    private fun postFeedback(client: Minecraft, message: String, color: ChatFormatting) {
        client.execute {
            client.player?.sendSystemMessage(Component.literal(message).withStyle(color))
        }
    }
}
