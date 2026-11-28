package com.archica.mcp.ui

import com.archica.mcp.ArchicaClientRuntime
import com.archica.mcp.blueprint.BlueprintPhaseNavigationResult
import com.archica.mcp.blueprint.BlueprintPhaseOverview
import com.archica.mcp.blueprint.PhaseViewMode
import kotlinx.coroutines.launch
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

/**
 * Interactive client screen for navigating semantic build phases, switching between
 * Isolated (`== k`), Cumulative (`1..k`), and All view modes, and inspecting per-phase materials.
 */
class ArchicaPhaseScreen(
    private val parent: Screen?,
    private val runtime: ArchicaClientRuntime,
) : Screen(Component.literal("Archica Build Phases")) {

    private var overviews: List<BlueprintPhaseOverview> = emptyList()
    private var selectedOverview: BlueprintPhaseOverview? = null
    private var loading: Boolean = true
    private var operationInFlight: Boolean = false
    private var statusMessage: String? = null
    private var statusColor: Int = COLOR_INFO

    private var pageOffset: Int = 0

    override fun init() {
        refreshWidgets()
        refreshOverviewsAsync()
    }

    private fun refreshWidgets() {
        clearWidgets()
        val panelWidth = 340
        val leftX = (width - panelWidth) / 2
        val startY = ((height - 250) / 2).coerceAtLeast(28)

        val current = selectedOverview
        val phases = current?.phases.orEmpty()
        val activeIdx = if (current != null && phases.isNotEmpty()) {
            phases.indexOfFirst { it.phaseId == current.phaseView.activePhaseId }.let { if (it < 0) 0 else it }
        } else {
            -1
        }
        val activeMode = current?.phaseView?.viewMode ?: PhaseViewMode.ISOLATED

        // Row 1: Blueprint switcher + View Mode toggle button
        val row1Y = startY + 18
        val bpBtnWidth = 166
        val bpLabel = current?.blueprintId ?: if (loading) "Loading..." else "None"
        addRenderableWidget(
            Button.builder(Component.literal("Blueprint: $bpLabel")) { cycleBlueprint() }
                .bounds(leftX, row1Y, bpBtnWidth, 20)
                .build()
                .apply { active = overviews.size > 1 && !operationInFlight },
        )

        addRenderableWidget(
            CycleButton.builder<PhaseViewMode>({ mode -> Component.literal(formatModeLabel(mode)) }, activeMode)
                .withValues(PhaseViewMode.ISOLATED, PhaseViewMode.CUMULATIVE, PhaseViewMode.ALL)
                .create(
                    leftX + bpBtnWidth + 8,
                    row1Y,
                    panelWidth - bpBtnWidth - 8,
                    20,
                    Component.literal("View"),
                ) { _, value ->
                    val bpId = selectedOverview?.blueprintId ?: return@create
                    applyPhaseAction { runtime.blueprintService.setPhaseViewMode(bpId, value) }
                }
                .apply { active = current != null && phases.isNotEmpty() && !operationInFlight },
        )

        // Row 2: Prev / Jump / Next navigation
        val row2Y = row1Y + 26
        val navBtnWidth = 74
        addRenderableWidget(
            Button.builder(Component.literal("< Prev")) {
                val bpId = selectedOverview?.blueprintId ?: return@builder
                applyPhaseAction { runtime.blueprintService.stepPhase(bpId, -1) }
            }
                .bounds(leftX, row2Y, navBtnWidth, 20)
                .build()
                .apply { active = current != null && activeIdx > 0 && !operationInFlight },
        )

        addRenderableWidget(
            Button.builder(Component.literal("Next >")) {
                val bpId = selectedOverview?.blueprintId ?: return@builder
                applyPhaseAction { runtime.blueprintService.stepPhase(bpId, 1) }
            }
                .bounds(leftX + panelWidth - navBtnWidth, row2Y, navBtnWidth, 20)
                .build()
                .apply { active = current != null && activeIdx in 0 until phases.lastIndex && !operationInFlight },
        )

        // Phase list rows (up to 4 visible clickable rows, with page buttons if > 4)
        val listStartY = row2Y + 26
        val visibleRows = 4
        val maxOffset = (phases.size - visibleRows).coerceAtLeast(0)
        pageOffset = pageOffset.coerceIn(0, maxOffset)

        val visibleSlice = phases.drop(pageOffset).take(visibleRows)
        for ((slot, phase) in visibleSlice.withIndex()) {
            val rowY = listStartY + slot * 22
            val isActive = phase.phaseId == current?.phaseView?.activePhaseId
            val marker = if (isActive) "* " else "  "
            val label = "$marker${phase.order}. ${phase.title} (${phase.totalBlocks} blks)"
            addRenderableWidget(
                Button.builder(Component.literal(label)) {
                    val bpId = selectedOverview?.blueprintId ?: return@builder
                    applyPhaseAction { runtime.blueprintService.setActivePhase(bpId, phase.phaseId) }
                }
                    .bounds(leftX, rowY, panelWidth, 20)
                    .build()
                    .apply { active = !operationInFlight },
            )
        }

        if (phases.size > visibleRows) {
            val pageY = listStartY + visibleRows * 22 + 2
            addRenderableWidget(
                Button.builder(Component.literal("Up")) {
                    pageOffset = (pageOffset - 1).coerceAtLeast(0)
                    refreshWidgets()
                }
                    .bounds(leftX, pageY, 50, 18)
                    .build()
                    .apply { active = pageOffset > 0 },
            )
            addRenderableWidget(
                Button.builder(Component.literal("Down")) {
                    pageOffset = (pageOffset + 1).coerceAtMost(maxOffset)
                    refreshWidgets()
                }
                    .bounds(leftX + panelWidth - 50, pageY, 50, 18)
                    .build()
                    .apply { active = pageOffset < maxOffset },
            )
        }

        val bottomY = startY + 228
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_DONE) { onClose() }
                .bounds(leftX, bottomY, panelWidth, 20)
                .build(),
        )
    }

    override fun extractRenderState(guiGraphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick)

        val panelWidth = 340
        val leftX = (width - panelWidth) / 2
        val startY = ((height - 250) / 2).coerceAtLeast(28)

        guiGraphics.centeredText(font, title, width / 2, (startY - 14).coerceAtLeast(8), COLOR_WHITE)

        val current = selectedOverview
        val phases = current?.phases.orEmpty()
        val activePhase = phases.firstOrNull { it.phaseId == current?.phaseView?.activePhaseId } ?: phases.firstOrNull()

        val headerLine = when {
            loading -> "Loading phased blueprints..."
            current == null -> "No phased blueprints found. Ask AI to tag ops with 'phase'."
            else -> {
                val activeTag = if (current.hasActivePlacement) "ACTIVE" else "DRAFT ONLY"
                "Status: $activeTag | Phases: ${phases.size} | Mode: ${formatModeLabel(current.phaseView.viewMode)}"
            }
        }
        guiGraphics.text(font, Component.literal(headerLine), leftX, startY + 4, COLOR_MUTED, false)

        if (activePhase != null) {
            val row2Y = startY + 18 + 26
            val centerText = "Phase ${activePhase.order}/${phases.size}: ${activePhase.title}"
            guiGraphics.centeredText(font, Component.literal(centerText), width / 2, row2Y + 6, COLOR_INFO)

            val detailsY = startY + 18 + 26 + 26 + 4 * 22 + (if (phases.size > 4) 22 else 4)
            val desc = activePhase.description?.take(62) ?: "Phase ID: ${activePhase.phaseId} (${activePhase.totalBlocks} blocks)"
            guiGraphics.text(font, Component.literal(desc), leftX, detailsY, COLOR_WHITE, false)

            val materialsText = formatTopMaterials(activePhase.materials, activePhase.survivalCheck?.missingMaterials)
            guiGraphics.text(font, Component.literal(materialsText), leftX, detailsY + 12, COLOR_MUTED, false)
        }

        statusMessage?.let { msg ->
            guiGraphics.centeredText(font, Component.literal(msg), width / 2, startY + 214, statusColor)
        }
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    private fun cycleBlueprint() {
        if (overviews.size <= 1) return
        val currentIdx = overviews.indexOfFirst { it.blueprintId == selectedOverview?.blueprintId }
        val next = overviews[(currentIdx + 1) % overviews.size]
        selectedOverview = next
        runtime.selectedPhasedBlueprintId.set(next.blueprintId)
        pageOffset = 0
        refreshWidgets()
    }

    private fun refreshOverviewsAsync() {
        loading = true
        runtime.scope.launch {
            val fetched = runCatching {
                val list = runtime.blueprintService.list().blueprints.filter { it.phaseCount > 0 }
                list.map { runtime.blueprintService.getPhaseOverview(it.blueprintId) }
            }.getOrElse { emptyList() }
            runOnClient {
                loading = false
                overviews = fetched
                val preferredId = runtime.selectedPhasedBlueprintId.get()
                selectedOverview = fetched.firstOrNull { it.blueprintId == preferredId }
                    ?: fetched.firstOrNull { it.hasActivePlacement }
                    ?: fetched.firstOrNull()
                selectedOverview?.let { runtime.selectedPhasedBlueprintId.set(it.blueprintId) }
                refreshWidgets()
            }
        }
    }

    private fun applyPhaseAction(action: suspend () -> BlueprintPhaseNavigationResult) {
        operationInFlight = true
        refreshWidgets()
        runtime.scope.launch {
            val result = runCatching { action() }
            runOnClient {
                operationInFlight = false
                result.onSuccess { nav ->
                    statusMessage = "Phase ${nav.activePhaseIndex}/${nav.totalPhases}: ${nav.activePhase.title} (${formatModeLabel(nav.viewMode)})"
                    statusColor = COLOR_SUCCESS
                    refreshOverviewsAsync()
                }.onFailure { err ->
                    statusMessage = err.message ?: err::class.simpleName
                    statusColor = COLOR_ERROR
                    refreshWidgets()
                }
            }
        }
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
        private const val COLOR_INFO = 0xFF55FFFF.toInt()
        private const val COLOR_ERROR = 0xFFFF5555.toInt()

        fun formatModeLabel(mode: PhaseViewMode): String = when (mode) {
            PhaseViewMode.ISOLATED -> "Isolated (Current)"
            PhaseViewMode.CUMULATIVE -> "Cumulative (1..Current)"
            PhaseViewMode.ALL -> "All Phases"
        }

        fun formatTopMaterials(materials: Map<String, Int>, missing: Map<String, Int>?): String {
            if (materials.isEmpty()) return "Materials: none"
            val top = materials.entries.take(3).joinToString(", ") { (id, count) ->
                val shortId = id.removePrefix("minecraft:")
                val miss = missing?.get(id)
                if (miss != null && miss > 0) "$shortId:$count (-$miss)" else "$shortId:$count"
            }
            val extra = if (materials.size > 3) " +${materials.size - 3} more" else ""
            return "Materials: $top$extra"
        }

        fun formatActionBar(nav: BlueprintPhaseNavigationResult): Component =
            Component.literal(
                "[${nav.blueprintId}] Phase ${nav.activePhaseIndex}/${nav.totalPhases}: " +
                    "${nav.activePhase.title} | ${formatModeLabel(nav.viewMode)} (${nav.activePhase.totalBlocks} blocks)",
            ).withStyle(ChatFormatting.AQUA)
    }
}
