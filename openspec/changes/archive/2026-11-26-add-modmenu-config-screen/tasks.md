# Tasks

## 1. Persistent Config & Port Precedence

- [x] 1.1 Add `ArchicaClientConfig` (`mcpPort`, `autoStartMcp`, `showPlacementFeedback` — explicitly excluding inventory tracking) and `ArchicaConfigStore` (JSON load/save with malformed/out-of-range fallback) in `src/main/kotlin/com/archica/mcp/config/ArchicaClientConfig.kt` (or pure-Kotlin module) and verify unit tests pass for defaults, save/load round-trip, corrupted JSON fallback, and exclusion of inventory state.
- [x] 1.2 Extend `McpPortConfig.resolvePort` in `src/main/kotlin/com/archica/mcp/server/McpServerModels.kt` to accept a `fallbackPort: Int = ArchicaModInfo.DEFAULT_MCP_PORT` so precedence is `-Darchica.mcp.port` > persisted `mcpPort` > `25580`, and verify `McpServerCoreTest` passes with new precedence and invalid-override fallback cases.

## 2. Optional Mod Menu Build & Entrypoint Setup

- [x] 2.1 Add TerraformersMC Maven repository (`https://maven.terraformersmc.com/`) and `modmenu_version` (`21.0.1`, verified against MC `26.3`) as `clientCompileOnly` in `gradle.properties` and `build.gradle.kts`, and verify `./gradlew compileClientKotlin` resolves cleanly.
- [x] 2.2 Update `src/main/resources/fabric.mod.json` to register the `"modmenu"` entrypoint (`com.archica.mcp.ui.ArchicaModMenuIntegration`) and add `"modmenu": "*"` under `"suggests"`, verifying `processResources` produces valid JSON without adding Mod Menu to `"depends"`.

## 3. Shared Client Runtime & Startup Lifecycle

- [x] 3.1 Refactor `src/client/kotlin/com/archica/mcp/ArchicaMcpClient.kt` to load `ArchicaConfigStore` from `FabricLoader.getInstance().configDir.resolve("archica-mcp.json")`, expose a shared `ArchicaClientRuntime` (holding `configStore`, `configRef`, `controller`, `blueprintService`, `litematicaBridge`, `scope`, `inventoryEnabled` initialized `false`), honor `autoStartMcp` during client startup, and gate `notifyPlacementFeedback` on `showPlacementFeedback`.
- [x] 3.2 Update `README.md` and `docs/antigravity-setup.md` to document `config/archica-mcp.json`, port resolution precedence (`-Darchica.mcp.port` > config file > `25580`), and `autoStartMcp` startup behavior, verifying documented keys and paths match `ArchicaClientConfig`.

## 4. Native Config Screen, Mod Menu Entry & Fallback Triggers

- [x] 4.1 Implement `ArchicaConfigScreen` (`net.minecraft.client.gui.screens.Screen`) in `src/client/kotlin/com/archica/mcp/ui/ArchicaConfigScreen.kt` with live MCP status/endpoint display, port `EditBox` with `1..65535` validation and inline feedback, **Start/Restart** and **Stop** buttons, **Copy MCP URL** clipboard action, persistent **Auto-start MCP** and **Placement Chat Notices** toggles, session-only **Inventory Tracking** toggle (labeled as resetting to `off` on restart), active `archica:` placement count + **Clear All** button, and **Save & Done** / **Cancel** actions.
- [x] 4.2 Implement `ArchicaModMenuIntegration` (`ModMenuApi`) in `src/client/kotlin/com/archica/mcp/ui/ArchicaModMenuIntegration.kt` returning `ArchicaConfigScreen(parent)` and verify `./gradlew build` compiles with `clientCompileOnly` Mod Menu without requiring Mod Menu at runtime.
- [x] 4.3 Add fallback access without Mod Menu: update `src/client/kotlin/com/archica/mcp/command/ArchicaClientCommands.kt` so bare `/archica` opens `ArchicaConfigScreen` (safely deferred past chat-screen dismissal) while preserving `/archica status|mcp|inventory|clear`, and register an optional unbound `KeyMapping` (`key.archica-mcp.open_config`, `InputConstants.UNKNOWN`) in `ArchicaMcpClient` + `src/client/resources/assets/archica-mcp/lang/en_us.json` (or existing resource root) with tick handler.
- [x] 4.4 Document Mod Menu config access, bare `/archica`, and optional keybind in `README.md` and `docs/antigravity-setup.md`, and verify all documented commands and UI controls match the implementation.

## 5. Verification

- [x] 5.1 Run `./gradlew test build` and `openspec validate add-modmenu-config-screen` (after implementation) to verify full unit test suite, Jar-in-Jar packaging, and build health.
