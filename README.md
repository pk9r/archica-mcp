# Archica MCP

> **Status:** v1 Core Implemented — Minecraft `26.3` Fabric client mod with embedded Streamable HTTP MCP server (11 tools), spatial context compression, stateful blueprint primitives, `.litematic` v7 compiler, Litematica placement bridge, and `/archica` client commands.

**Archica MCP** is a Minecraft client-side mod (Fabric + Fabric Language Kotlin) that turns your local AI assistant (Antigravity, Claude, Cursor, or any MCP-compatible IDE) into an interactive architectural co-builder using **Litematica** holograms.

Instead of giving an AI raw world-editing commands that can grief builds or require server operator permissions, Archica MCP exposes a **localhost Streamable HTTP MCP server** directly inside the Minecraft client. The agent inspects a token-efficient summary of your surroundings, builds or refines a blueprint using high-level geometric and architectural primitives, compiles it to a standard `.litematic` file, and automatically loads the hologram into Litematica for you to preview and build.

---

## How It Works

```text
+----------------------------------+         Streamable HTTP (MCP)         +---------------------------------------+
|  Local AI Client                 | <-----------------------------------> |  Archica MCP (Fabric Client + Kotlin) |
|  (Antigravity / Claude / IDE)    |    http://127.0.0.1:25580/mcp         |                                       |
+----------------------------------+                                       |  1. Spatial Context Scanner           |
                                                                           |     (Player radius + inventory)       |
  Loop:                                                                    |  2. Stateful Blueprint Session        |
  1. Inspect player surroundings                                           |     (Anchor-relative primitives)      |
  2. Draft / refine blueprint ops                                          |  3. .litematic NBT Compiler           |
  3. Compile & preview hologram                                            |  4. Litematica Placement Bridge       |
  4. Player reviews -> AI iterates                                         +-------------------+-------------------+
                                                                                               |
                                                                                               v
                                                                           +---------------------------------------+
                                                                           |  Litematica Mod (Client Hologram)     |
                                                                           |  * Renders ghost blocks at anchor     |
                                                                           |  * Tracks materials & build layers    |
                                                                           +---------------------------------------+
```

---

## Key Capabilities (v1)

- **Embedded Localhost MCP Server**: Runs directly inside the Minecraft client JVM using `io.modelcontextprotocol:kotlin-sdk` (`0.16.0`) + Ktor CIO Streamable HTTP (`127.0.0.1:25580/mcp`), requiring no external runtime or separate Node/Python wrapper.
- **Concise Spatial Context**: Summarizes terrain around the player (`radius = 12` default, `4..24` range) into a compact 2D relative heightmap, surface block legend, pose/anchor suggestion, and opt-in hotbar/inventory material counts (off by default on every restart unless enabled in-session via the config screen or `/archica inventory on`), plus on-demand `scan_volume` 3D RLE slices.
- **Stateful Blueprint Sessions**: Agents create named in-memory drafts (`blueprint_id`) and iteratively append, update, remove, or undo operations instead of regenerating entire builds from scratch on every revision.
- **High-Level Building Primitives**: Express structures with `set_block`, `fill_box`, `hollow_box`, `line`, `cylinder`, `dome`, `gable_roof`, `staircase`, `replace_box`, `clear_box`, and `repeat_pattern` using anchor-relative `(0, 0, 0)` coordinates and automatic blockstate orientation (`stairs`, `logs`, `doors`, horizontal `connectable` panes/fences).
- **File-Backed `.litematic` Compilation & Auto-Activation**: Writes standard `.litematic` NBT files under `.minecraft/schematics/archica/` and automatically loads/replaces the active Litematica placement so refinements update the in-world hologram without stacking duplicates.
- **In-Game Config Screen & Client Controls**: Configure `mcpPort`, `autoStartMcp`, and `showPlacementFeedback` (persisted in `config/archica-mcp.json`) plus session-only inventory tracking, live MCP start/stop/restart, clipboard URL copy, and active hologram clearance from **Mod Menu** (`Esc -> Mods -> Archica MCP -> Config`), bare `/archica`, or the optional **Open Archica Config** keybind — alongside `/archica status`, `/archica inventory [on|off]`, `/archica mcp start [port]`, `/archica mcp stop`, and `/archica clear <blueprint_id|all>`.

---

## Target Dependency Baseline

| Component | Version | Notes |
|---|---|---|
| Minecraft (Java Edition) | `26.3` | Fabric Loader `>=0.20.1`, Java `25` |
| Fabric API | `0.167.0+26.3` | Client lifecycle, client commands |
| Fabric Language Kotlin | `1.14.2+kotlin.2.4.21` | Kotlin runtime & stdlib for Fabric |
| Litematica | `0.29.1` | Schematic loading & hologram rendering |
| MaLiLib | `0.30.2` | Required dependency for Litematica |
| MCP Kotlin SDK | `0.16.0` | `io.modelcontextprotocol:kotlin-sdk-server` |
| Ktor Server (CIO) | `3.5.2` | Embedded Streamable HTTP transport |

---

## Build & Local MCP Client Setup

### Building the Mod Jar

```bash
./gradlew test build
```

The packaged mod jar (including Jar-in-Jar Ktor/MCP dependencies) is output to `build/libs/archica-mcp-0.1.0.jar`. Place it in your Fabric `26.3` `mods/` directory alongside **Fabric API**, **Fabric Language Kotlin**, **MaLiLib**, and **Litematica** (and optionally **Mod Menu** to open the config screen from `Esc -> Mods`).

### Connecting an MCP Client

Launch Minecraft and join a world (or even draft offline on the title screen). By default (`autoStartMcp = true`), Archica MCP binds strictly to loopback at:

```text
http://127.0.0.1:25580/mcp
```

Configure and manage the mod without memorizing slash commands via:
- **Mod Menu** *(optional)*: `Esc -> Mods -> Archica MCP -> Config`
- **Quick command / keybind**: Run `/archica` (no arguments) in chat, or bind **Open Archica Config** under *Options -> Controls -> Key Binds* (unbound by default)
- **Persisted config file** (`.minecraft/config/archica-mcp.json`): stores `mcpPort` (`25580`), `autoStartMcp` (`true`), and `showPlacementFeedback` (`true`). Inventory tracking is **never** persisted and always resets to `off` on restart.
- **Startup port precedence**: `-Darchica.mcp.port=<port>` > `config/archica-mcp.json` (`mcpPort`) > `25580`.
- **Slash subcommands** *(also available)*: `/archica status`, `/archica inventory [on|off]`, `/archica mcp start [port]`, `/archica mcp stop`, and `/archica clear <blueprint_id|all>`.

### Example MCP Client Configuration

For MCP clients that support Streamable HTTP servers directly:

```json
{
  "mcpServers": {
    "archica-mcp": {
      "type": "streamable-http",
      "url": "http://127.0.0.1:25580/mcp"
    }
  }
}
```

> **Note:** Exact configuration keys (`type` vs `transport`, `url` vs `serverUrl`) vary slightly by client (Antigravity, Claude Desktop, Cursor, VS Code). Only loopback hosts (`127.0.0.1`, `localhost`, `[::1]`) are permitted by default via DNS-rebinding protection.

#### Antigravity (Workspace-Scoped Plugin + 5x5 Building Skills)

This repository includes a pre-packaged workspace plugin under [`.agents/plugins/archica-mcp/`](.agents/plugins/archica-mcp/) (`"disabled": true` by default so mod development doesn't attempt to connect when Minecraft is closed) bundling the MCP config alongside 4 **5x5 Shared-Pillar** agent skills (`archica-mcp-workflow`, `archica-5x5-architecture`, `archica-terrain-survival`, `archica-renovation-interior`). Toggle it on in Antigravity Customizations while Minecraft is running, or copy `.agents/plugins/archica-mcp/` into any external build workspace:

- `.agents/plugins/archica-mcp/plugin.json` (remove `"disabled": true` if you want it enabled immediately in a dedicated build workspace):

```json
{
  "name": "archica-mcp",
  "disabled": true
}
```

- `.agents/plugins/archica-mcp/mcp_config.json`:

```json
{
  "mcpServers": {
    "archica-mcp": {
      "url": "http://127.0.0.1:25580/mcp"
    }
  }
}
```

See the [Antigravity Setup Guide](docs/antigravity-setup.md) for sharing across selected workspaces via `plugins.json`, UI verification, custom ports, and troubleshooting.

---

## Documentation

- [Antigravity Setup Guide](docs/antigravity-setup.md) — Non-global workspace plugin setup, on-demand toggling, custom ports, and troubleshooting.
- [Architecture & Litematica Integration](docs/architecture.md) — Embedded Ktor MCP server, thread marshalling to the Minecraft client thread, `.litematic` NBT compilation, and Litematica placement replacement.
- [Spatial Context Compression](docs/spatial-context.md) — Player-radius terrain scanning, 2D heightmap + legend format, inventory material summaries, and `scan_volume` RLE slices.
- [Stateful Blueprints & Building Primitives](docs/blueprint-primitives.md) — Session lifecycle, relative coordinates, auto-facing blockstate rules, and the v1 primitive catalog.
- [MCP Tools Reference](docs/mcp-tools.md) — Implemented schemas for all 11 tools, request/response payloads, survival material warnings, and error codes.

---

## Status & Roadmap

1. **Documentation & Design Baseline** *(Done)* — Architecture, spatial compression, primitives, and MCP contracts.
2. **Project Scaffolding & Embedded MCP Server** *(Done)* — Gradle + Fabric + Kotlin setup with Ktor CIO Streamable HTTP `/mcp` endpoint and client-thread dispatcher.
3. **Player-Radius Spatial Scanner** *(Done)* — World context extraction, heightmap + surface legend encoding, inventory inspection, and RLE `scan_volume`.
4. **Blueprint Session Store & Primitive Compiler** *(Done)* — In-memory operations, voxel rasterization, auto-orientation, and `.litematic` NBT writer.
5. **Litematica Bridge & Client UX** *(Done)* — Automatic schematic placement/replacement, survival material checks, `/archica` client commands, and chat feedback.
