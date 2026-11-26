# Design

## Context

`ArchicaMcpClient` currently initializes `ArchicaMcpServerController`, `BlueprintService`, `ClientLitematicaBridge`, `CoroutineScope`, and `inventoryEnabled` (`AtomicBoolean(false)`) inside `onInitializeClient()`, registers `/archica status|inventory|mcp|clear` in `ArchicaClientCommands`, and unconditionally starts the embedded Ktor CIO MCP server on the port returned by `McpPortConfig.resolvePort()`. There is no persistent config file, no UI screen, and no Mod Menu integration.

Mod Menu (`com.terraformersmc:modmenu`) uses a separate entrypoint (`"modmenu"` in `fabric.mod.json`) instantiated independently from `ClientModInitializer`, so client runtime dependencies needed by `ArchicaConfigScreen` must be accessible through a shared client runtime holder rather than hidden inside local variables of `onInitializeClient()`.

See `proposal.md` for motivation and scope, and `specs/client-ux/spec.md` + `specs/mcp-server/spec.md` for behavioral contracts.

## Goals / Non-Goals

**Goals:**
- Provide a clean, dependency-free native Minecraft `Screen` (`ArchicaConfigScreen`) using standard `Button`, `CycleButton`, and `EditBox` widgets.
- Integrate with Mod Menu as a strictly optional compile-time dependency (`clientCompileOnly`) and `"suggests"` metadata so the mod works identically with or without Mod Menu.
- Persist non-privacy preferences (`mcpPort`, `autoStartMcp`, `showPlacementFeedback`) atomically/safely to `FabricLoader.getInstance().configDir.resolve("archica-mcp.json")`.
- Guarantee `inventoryEnabled` remains purely in-memory and starts `false` on every launch.
- Provide frictionless fallback access (`bare /archica` command + optional unbound `KeyMapping`) while keeping existing `/archica ...` subcommands intact.

**Non-Goals:**
- Building a MaLiLib `GuiConfigsBase` screen or adding Cloth Config as a dependency.
- Persisting `inventoryEnabled` across client restarts or adding a "remember inventory opt-in" setting.
- Changing any MCP tool schemas, `.litematic` compilation, or Litematica placement behavior.

## Decisions

### 1. Native Minecraft `Screen` over MaLiLib `GuiConfigsBase` or Cloth Config
- **Choice**: Subclass `net.minecraft.client.gui.screens.Screen` directly in `src/client/kotlin/com/archica/mcp/ui/ArchicaConfigScreen.kt`.
- **Rationale**: Mod Menu accepts any vanilla `Screen`. Native `Screen` gives full layout freedom to combine persistent settings (`mcpPort`, `autoStartMcp`, `showPlacementFeedback`) with live runtime controls (`Start/Restart`, `Stop`, `Copy MCP URL`, session-only `Inventory Tracking` toggle, and `Clear All` active placements) without forcing action buttons into MaLiLib's key-value config table or adding Cloth Config as a runtime dependency.
- **Alternative**: MaLiLib `GuiConfigsBase` (already on classpath) — visually matches Litematica's `M + C` menu, but awkward for live dashboard actions like copying the endpoint URL, showing inline port bind errors, or clearing active holograms. Can still be linked later if desired.

### 2. Optional Mod Menu Integration (`clientCompileOnly`)
- **Choice**: Add TerraformersMC Maven (`https://maven.terraformersmc.com/`) and `"clientCompileOnly"("com.terraformersmc:modmenu:${property("modmenu_version")}")` (targeting MC `26.3`), register `"modmenu": [{"adapter": "kotlin", "value": "com.archica.mcp.ui.ArchicaModMenuIntegration"}]` in `fabric.mod.json`, and list `"modmenu": "*"` under `"suggests"` (never `"depends"`).
- **Rationale**: Fabric Loader only loads the `"modmenu"` entrypoint class when Mod Menu itself loads entrypoints, so `ArchicaModMenuIntegration` is never class-loaded if Mod Menu is absent. Zero extra Jar-in-Jar bloat and zero hard runtime requirement.
- **Alternative**: `clientImplementation` + `"depends"` — forces every user to install Mod Menu, breaking minimal Litematica-only setups.

### 3. Shared Client Config Store & Runtime Holder
- **Choice**:
  - Pure-Kotlin `ArchicaClientConfig` (`@Serializable data class(val mcpPort: Int = 25580, val autoStartMcp: Boolean = true, val showPlacementFeedback: Boolean = true)`) + `ArchicaConfigStore(configPath: Path)` using `kotlinx.serialization.json.Json` (already provided by `fabric-language-kotlin`).
  - `McpPortConfig.resolvePort(rawProperty = ..., fallbackPort = config.mcpPort)` extends the existing pure-Kotlin helper so `-Darchica.mcp.port` keeps top precedence and unit tests remain fast and JVM-only.
  - Hold initialized client services (`configStore`, `controller`, `blueprintService`, `litematicaBridge`, `scope`, `inventoryEnabled`, `configState`) in a lightweight `ArchicaClientRuntime` reference exposed by `ArchicaMcpClient`.
- **Rationale**: Decouples config persistence from Minecraft classes for fast JUnit tests while letting `ArchicaModMenuIntegration`, bare `/archica`, and the keybind handler open `ArchicaConfigScreen` against the exact same live controller and `inventoryEnabled` flag.
- **Note on `-Darchica.mcp.port` override in UI**: If `-Darchica.mcp.port` was passed at launch, the server initially binds to that override port; changing `mcpPort` in the UI updates `config/archica-mcp.json` and restarts the server on the new port immediately for the current session (matching `/archica mcp start <port>`).

### 4. Fallback Entrypoints Without Mod Menu
- **Choice**:
  - Register `.executes { ... }` directly on the root `ClientCommands.literal("archica")` node in `ArchicaClientCommands` so typing `/archica` opens `ArchicaConfigScreen` (via `client.execute { client.setScreen(ArchicaConfigScreen(null, ...)) }`, noting chat closes right as command runs, so scheduling on `client.execute` avoids the closing chat screen wiping the new screen).
  - Register a Fabric `KeyMapping` (`key.archica-mcp.open_config`, category `Archica MCP`, `InputConstants.UNKNOWN` by default) via `KeyBindingHelper.registerKeyBinding` and `ClientTickEvents.END_CLIENT_TICK`.
- **Rationale**: Bare `/archica` is easy to remember for users without Mod Menu, `UNKNOWN` default keybind avoids conflicting with Litematica's dense hotkeys (`M`, `C`, etc.), and existing `/archica status|mcp|inventory|clear` commands remain unchanged.

### 5. Async Screen Actions & Thread Safety
- **Choice**: `ArchicaConfigScreen` launches `controller.start(port)`, `controller.stop()`, and `litematicaBridge.clearPlacement(...)` on the shared `CoroutineScope` (`Dispatchers.IO`) and marshals status/error updates back to the Minecraft render thread via `minecraft?.execute { ... }`, disabling action buttons briefly while an operation is in flight or refreshing widget states on completion.
- **Rationale**: Keeps `Screen` rendering responsive and avoids blocking the client main thread on Ktor CIO socket bind/stop or Litematica coroutine dispatch.

## Risks / Trade-offs

- **Mod Menu version availability on TerraformersMC Maven for MC `26.3`** -> Verify exact `modmenu_version` (`21.0.1` / `21.0.0`) resolution in Gradle before coding; because it is `clientCompileOnly`, no runtime Jar-in-Jar compatibility risk exists.
- **Minecraft `Screen` chat-close race on `/archica` command** -> Pressing Enter in `ChatScreen` runs the command and then closes `ChatScreen` (`setScreen(null)`). Scheduling `client.setScreen(...)` on the next client tick (or via a pending-open flag checked in `ClientTickEvents.END_CLIENT_TICK` / `client.execute`) prevents `ChatScreen` cleanup from immediately dismissing `ArchicaConfigScreen`.
- **Port field unsaved vs applied confusion** -> Make the port control explicit: changing the text field and clicking **Apply & Restart** (or **Save & Done** when valid) validates `1..65535`, writes `config/archica-mcp.json`, and restarts the MCP server if running (or updates configured port if stopped), showing clear inline green/red feedback.
