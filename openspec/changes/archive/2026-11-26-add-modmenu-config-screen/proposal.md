# Proposal

## Why

Players currently have to memorize multiple `/archica` slash commands (`/archica status`, `/archica mcp start|stop`, `/archica inventory on|off`, `/archica clear <id|all>`) and pass `-Darchica.mcp.port=<port>` in launcher JVM arguments just to configure and operate Archica MCP. Adding a native Minecraft config screen accessible from Mod Menu (`Esc -> Mods -> Archica MCP -> Config`), backed by a persistent JSON config file for non-sensitive preferences, makes setup and daily control effortless while keeping session-only privacy defaults intact.

## What Changes

- Add optional **Mod Menu** (`ModMenuApi`) integration in `fabric.mod.json` and `build.gradle.kts` (`compileOnly` / `suggests`) so Mod Menu is not required at runtime.
- Add a native Minecraft **`ArchicaConfigScreen`** (`Screen`) providing:
  - Live embedded MCP server status, port editor with validation & inline error feedback, **Start/Restart** and **Stop** actions, and a **Copy MCP URL** clipboard button.
  - Persistent **Auto-start MCP on launch** and **Placement chat notices** toggles.
  - Session-only **Inventory Tracking** toggle clearly marked as resetting to `off` on every client restart.
  - Active `archica:` Litematica placement counter and one-click **Clear All** action when a world is loaded.
- Persist non-privacy client preferences (`mcpPort`, `autoStartMcp`, `showPlacementFeedback`) in `.minecraft/config/archica-mcp.json`, with startup port precedence `-Darchica.mcp.port` > config file > `25580`.
- Keep **Inventory Tracking** strictly in-memory and reset to `off` on every Minecraft launch (never persisted to disk).
- Add fallback ways to open `ArchicaConfigScreen` without Mod Menu: running bare `/archica` with no subcommands and an optional unbound keybind in Minecraft Controls, while preserving all existing `/archica status|mcp|inventory|clear` subcommands.

## Capabilities

### New Capabilities
<!-- None; this extends existing client UX and MCP server startup configuration capabilities. -->

### Modified Capabilities
- `client-ux`: Add native Minecraft config screen, optional Mod Menu integration, persistent JSON config for non-privacy preferences, bare `/archica` and optional unbound keybind entrypoints, and placement notice toggle while preserving session-only `off` default for inventory tracking and existing slash subcommands.
- `mcp-server`: Update startup port resolution precedence (`-Darchica.mcp.port` > persisted config > `25580`) and respect the `autoStartMcp` client preference during client initialization.

## Impact

- **Build & Metadata**: `gradle.properties`, `build.gradle.kts` (TerraformersMC Maven + `modmenu` `clientCompileOnly`), `src/main/resources/fabric.mod.json` (`modmenu` entrypoint + `suggests`).
- **Client Runtime & UI**: New config model/store and `ArchicaConfigScreen` under `src/client/kotlin/com/archica/mcp/...`, refactored client runtime state shared across `ArchicaMcpClient`, `ArchicaClientCommands`, and the Mod Menu entrypoint.
- **Server Config**: `McpPortConfig` in `src/main/kotlin/com/archica/mcp/server/McpServerModels.kt` (supporting fallback port parameter alongside `-Darchica.mcp.port`).
- **Tests & Docs**: Unit tests for config JSON persistence, validation, and port precedence; updated user documentation in `README.md` and `docs/antigravity-setup.md`.
