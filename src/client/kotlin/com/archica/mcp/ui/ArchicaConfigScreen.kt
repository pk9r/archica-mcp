package com.archica.mcp.ui

import com.archica.mcp.ArchicaClientRuntime
import com.archica.mcp.config.ArchicaClientConfig
import com.archica.mcp.server.ArchicaMcpServerController
import kotlinx.coroutines.launch
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

/**
 * Native Minecraft configuration and live control screen for Archica MCP.
 */
class ArchicaConfigScreen(
    private val parent: Screen?,
    private val runtime: ArchicaClientRuntime,
) : Screen(Component.literal("Archica MCP Configuration")) {

    private var draftConfig: ArchicaClientConfig = runtime.configRef.get()
    private val initialSavedPort: Int = draftConfig.mcpPort

    private var portBox: EditBox? = null
    private var startRestartButton: Button? = null
    private var stopButton: Button? = null
    private var copyUrlButton: Button? = null
    private var clearAllButton: Button? = null
    private var phasesButton: Button? = null

    private var activePlacementCount: Int? = null
    private var operationInFlight: Boolean = false
    private var feedbackText: String? = null
    private var feedbackColor: Int = COLOR_INFO

    override fun init() {
        val panelWidth = 320
        val leftX = (width - panelWidth) / 2
        val startY = ((height - 240) / 2).coerceAtLeast(32)

        val currentPortText = portBox?.value ?: draftConfig.mcpPort.toString()

        // Row 1 (after status block): Port input + Start/Restart + Stop + Copy URL
        val row1Y = startY + 34
        val portInput = EditBox(font, leftX, row1Y, 64, 20, Component.literal("MCP Port")).apply {
            setMaxLength(5)
            value = currentPortText
        }
        portBox = addRenderableWidget(portInput)

        startRestartButton = addRenderableWidget(
            Button.builder(Component.literal("Start")) { onApplyAndStartOrRestart() }
                .bounds(leftX + 68, row1Y, 108, 20)
                .build(),
        )

        stopButton = addRenderableWidget(
            Button.builder(Component.literal("Stop")) { onStopServer() }
                .bounds(leftX + 180, row1Y, 54, 20)
                .build(),
        )

        copyUrlButton = addRenderableWidget(
            Button.builder(Component.literal("Copy URL")) { onCopyEndpointUrl() }
                .bounds(leftX + 238, row1Y, 82, 20)
                .build(),
        )

        // Row 2: Auto-start MCP on launch (persistent)
        val row2Y = row1Y + 28
        addRenderableWidget(
            CycleButton.onOffBuilder(draftConfig.autoStartMcp)
                .create(leftX, row2Y, panelWidth, 20, Component.literal("Auto-start MCP on Launch")) { _, value ->
                    draftConfig = draftConfig.copy(autoStartMcp = value)
                },
        )

        // Row 3: Placement chat notices (persistent)
        val row3Y = row2Y + 24
        addRenderableWidget(
            CycleButton.onOffBuilder(draftConfig.showPlacementFeedback)
                .create(leftX, row3Y, panelWidth, 20, Component.literal("Placement Chat Notices")) { _, value ->
                    draftConfig = draftConfig.copy(showPlacementFeedback = value)
                },
        )

        // Row 4: Inventory tracking (strictly session-only, resets to OFF on restart)
        val row4Y = row3Y + 24
        addRenderableWidget(
            CycleButton.onOffBuilder(runtime.inventoryEnabled.get())
                .create(
                    leftX,
                    row4Y,
                    panelWidth,
                    20,
                    Component.literal("Inventory Tracking (Resets OFF on restart)"),
                ) { _, value ->
                    runtime.inventoryEnabled.set(value)
                    setFeedback(
                        if (value) {
                            "Inventory tracking ON for this session only."
                        } else {
                            "Inventory tracking OFF (player inventory ignored)."
                        },
                        COLOR_INFO,
                    )
                },
        )

        // Row 5: Build Phases & Clear All active Archica placements
        val row5Y = row4Y + 28
        val halfPanel = (panelWidth - 8) / 2
        phasesButton = addRenderableWidget(
            Button.builder(Component.literal("Build Phases...")) {
                minecraft.gui.setScreen(ArchicaPhaseScreen(this, runtime))
            }
                .bounds(leftX, row5Y, halfPanel, 20)
                .build(),
        )
        clearAllButton = addRenderableWidget(
            Button.builder(Component.literal("Clear Holograms")) { onClearAllPlacements() }
                .bounds(leftX + halfPanel + 8, row5Y, halfPanel, 20)
                .build(),
        )

        // Bottom row: Save & Done / Cancel
        val bottomY = row5Y + 48
        val halfWidth = (panelWidth - 8) / 2
        addRenderableWidget(
            Button.builder(Component.literal("Save & Done")) { onSaveAndClose() }
                .bounds(leftX, bottomY, halfWidth, 20)
                .build(),
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) { onClose() }
                .bounds(leftX + halfWidth + 8, bottomY, halfWidth, 20)
                .build(),
        )

        refreshControlStates()
        refreshActivePlacementsAsync()
    }

    override fun extractRenderState(guiGraphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick)

        val panelWidth = 320
        val leftX = (width - panelWidth) / 2
        val startY = ((height - 240) / 2).coerceAtLeast(32)

        guiGraphics.centeredText(font, title, width / 2, (startY - 20).coerceAtLeast(10), COLOR_WHITE)

        val status = runtime.controller.status()
        val stateLabel = if (status.running) "RUNNING" else "STOPPED"
        val stateColor = if (status.running) COLOR_SUCCESS else COLOR_WARNING
        guiGraphics.text(font, Component.literal("MCP Server: $stateLabel"), leftX, startY, stateColor, true)
        guiGraphics.text(font, Component.literal("Endpoint: ${status.endpointUrl}"), leftX, startY + 12, COLOR_MUTED, true)
        guiGraphics.text(font, Component.literal("Port (1..65535)"), leftX, startY + 24, COLOR_MUTED, false)

        val worldLoaded = minecraft.level != null && minecraft.player != null
        val placementSummary = when {
            !worldLoaded -> "Active Archica Holograms: join a world to manage"
            activePlacementCount == null -> "Active Archica Holograms: checking..."
            else -> "Active Archica Holograms: $activePlacementCount"
        }
        val row5Y = startY + 34 + 28 + 24 + 24 + 28
        guiGraphics.text(font, Component.literal(placementSummary), leftX, row5Y + 23, COLOR_MUTED, false)

        feedbackText?.let { msg ->
            guiGraphics.centeredText(font, Component.literal(msg), width / 2, row5Y + 33, feedbackColor)
        }
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    private fun onApplyAndStartOrRestart() {
        val parsedPort = parsePortInputOrReport() ?: return
        val updated = persistDraftConfig(parsedPort)
        draftConfig = updated
        operationInFlight = true
        refreshControlStates()
        setFeedback("Applying port $parsedPort...", COLOR_INFO)

        runtime.scope.launch {
            val outcome = runtime.controller.start(parsedPort)
            runOnClient {
                operationInFlight = false
                when (outcome) {
                    is ArchicaMcpServerController.StartOutcome.Started -> {
                        val verb = if (outcome.restarted) "Restarted" else "Started"
                        setFeedback("$verb MCP server at ${outcome.status.endpointUrl}", COLOR_SUCCESS)
                    }
                    is ArchicaMcpServerController.StartOutcome.AlreadyRunning -> {
                        setFeedback("MCP server already running at ${outcome.status.endpointUrl}", COLOR_INFO)
                    }
                    is ArchicaMcpServerController.StartOutcome.InvalidPort -> {
                        setFeedback("Invalid port ${outcome.requestedPort}: expected 1..65535.", COLOR_ERROR)
                    }
                    is ArchicaMcpServerController.StartOutcome.BindFailed -> {
                        setFeedback("Failed to bind ${outcome.status.endpointUrl} (port may be in use).", COLOR_ERROR)
                    }
                }
                refreshControlStates()
            }
        }
    }

    private fun onStopServer() {
        operationInFlight = true
        refreshControlStates()
        runtime.scope.launch {
            val stopped = runtime.controller.stop()
            runOnClient {
                operationInFlight = false
                setFeedback(
                    if (stopped) "Embedded MCP server stopped." else "Embedded MCP server is already stopped.",
                    COLOR_WARNING,
                )
                refreshControlStates()
            }
        }
    }

    private fun onCopyEndpointUrl() {
        val status = runtime.controller.status()
        val parsedPort = portBox?.value?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }
        val url = if (!status.running && parsedPort != null) {
            "http://${status.host}:$parsedPort/mcp"
        } else {
            status.endpointUrl
        }
        minecraft.keyboardHandler.clipboard = url
        setFeedback("Copied $url to clipboard.", COLOR_SUCCESS)
    }

    private fun onClearAllPlacements() {
        operationInFlight = true
        refreshControlStates()
        runtime.scope.launch {
            val result = runCatching {
                val activeIds = runtime.litematicaBridge.listActivePlacements().sorted()
                var clearedCount = 0
                for (id in activeIds) {
                    if (runtime.litematicaBridge.clearPlacement(id)) {
                        clearedCount++
                    }
                }
                clearedCount
            }
            runOnClient {
                operationInFlight = false
                result.onSuccess { count ->
                    activePlacementCount = 0
                    setFeedback(
                        if (count == 0) {
                            "No active Archica placements found."
                        } else {
                            "Cleared $count active placement(s) (.litematic files kept)."
                        },
                        COLOR_INFO,
                    )
                }.onFailure { t ->
                    setFeedback("Failed to clear placements: ${t.message ?: t::class.simpleName}", COLOR_ERROR)
                }
                refreshControlStates()
                refreshActivePlacementsAsync()
            }
        }
    }

    private fun onSaveAndClose() {
        val parsedPort = parsePortInputOrReport() ?: return
        val saved = persistDraftConfig(parsedPort)
        val status = runtime.controller.status()
        if (status.running && parsedPort != status.port && parsedPort != initialSavedPort) {
            operationInFlight = true
            refreshControlStates()
            runtime.scope.launch {
                val outcome = runtime.controller.start(parsedPort)
                runOnClient {
                    operationInFlight = false
                    if (outcome is ArchicaMcpServerController.StartOutcome.BindFailed) {
                        setFeedback("Saved config, but failed to bind port $parsedPort.", COLOR_ERROR)
                        refreshControlStates()
                    } else {
                        onClose()
                    }
                }
            }
        } else {
            if (!status.running && parsedPort != status.port) {
                runtime.controller.updateConfiguredPort(parsedPort)
            }
            draftConfig = saved
            onClose()
        }
    }

    private fun persistDraftConfig(port: Int): ArchicaClientConfig {
        val next = draftConfig.copy(mcpPort = port)
        val saved = runtime.configStore.save(next)
        runtime.configRef.set(saved)
        return saved
    }

    private fun parsePortInputOrReport(): Int? {
        val raw = portBox?.value?.trim().orEmpty()
        val parsed = raw.toIntOrNull()
        if (parsed == null || parsed !in 1..65535) {
            setFeedback("Invalid port '$raw': enter an integer between 1 and 65535.", COLOR_ERROR)
            return null
        }
        return parsed
    }

    private fun refreshActivePlacementsAsync() {
        if (minecraft.level == null || minecraft.player == null) {
            activePlacementCount = null
            refreshControlStates()
            return
        }
        runtime.scope.launch {
            val count = runCatching { runtime.litematicaBridge.listActivePlacements().size }.getOrNull()
            runOnClient {
                activePlacementCount = count
                refreshControlStates()
            }
        }
    }

    private fun refreshControlStates() {
        val status = runtime.controller.status()
        startRestartButton?.apply {
            message = Component.literal(if (status.running) "Apply & Restart" else "Start")
            active = !operationInFlight
        }
        stopButton?.active = status.running && !operationInFlight
        copyUrlButton?.active = !operationInFlight

        val worldLoaded = minecraft.level != null && minecraft.player != null
        val hasPlacements = (activePlacementCount ?: 0) > 0
        phasesButton?.active = !operationInFlight
        clearAllButton?.active = worldLoaded && hasPlacements && !operationInFlight
    }

    private fun setFeedback(message: String, color: Int) {
        feedbackText = message
        feedbackColor = color
    }

    private fun runOnClient(block: () -> Unit) {
        minecraft.execute {
            if (minecraft.gui.screen() === this) {
                block()
            }
        }
    }

    companion object {
        private const val COLOR_WHITE = 0xFFFFFFFF.toInt()
        private const val COLOR_MUTED = 0xFFAAAAAA.toInt()
        private const val COLOR_SUCCESS = 0xFF55FF55.toInt()
        private const val COLOR_WARNING = 0xFFFFFF55.toInt()
        private const val COLOR_INFO = 0xFF55FFFF.toInt()
        private const val COLOR_ERROR = 0xFFFF5555.toInt()
    }
}
