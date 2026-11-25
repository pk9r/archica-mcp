# Antigravity Setup Guide (Workspace-Scoped)

> **Status:** v1 Antigravity Integration Guide — connects **Antigravity IDE**, **Antigravity 2.0**, or **Antigravity CLI (`agy`)** to Archica MCP using a workspace-scoped plugin without modifying global MCP settings.

In Antigravity's customization system, a standalone `mcp_config.json` only applies globally (`~/.gemini/config/mcp_config.json`). To keep Archica MCP **isolated to specific Minecraft workspaces** (and easily toggleable on/off without polluting unrelated repositories), package its `mcp_config.json` inside a workspace plugin under `.agents/plugins/`.

```text
+---------------------------------------------------+       Streamable HTTP        +----------------------------------+
|  Workspace Root                                   |                              |  Minecraft Client JVM            |
|  <workspace>/.agents/plugins/archica-mcp/         |   http://127.0.0.1:25580/mcp |  Archica MCP (Loopback Only)     |
|    |-- plugin.json       (declares plugin)        | <--------------------------> |  * /archica status               |
|    |-- mcp_config.json   ("url": "...:25580/mcp") |                              |  * 11 MCP tools + Litematica     |
|    +-- skills/           (4 5x5 building skills)  |                              +----------------------------------+
+---------------------------------------------------+
```

---

## 1. Prerequisites

- Minecraft `26.3` with **Fabric Loader** (`>=0.20.1`), **Fabric API**, **Fabric Language Kotlin**, **MaLiLib**, **Litematica**, and `archica-mcp-0.1.0.jar` in your `mods/` folder (see [README.md](../README.md#build--local-mcp-client-setup)).
- Launch Minecraft before connecting Antigravity:
  - The embedded MCP server starts automatically at startup (even on the title screen) on `http://127.0.0.1:25580/mcp`.
  - Join a world before invoking world-reading or hologram placement tools (otherwise tools return `NO_WORLD_LOADED`).

---

## 2. Workspace Plugin Setup (Non-Global)

This repository already ships a complete workspace plugin under [`.agents/plugins/archica-mcp/`](../.agents/plugins/archica-mcp/) (disabled by default via `"disabled": true` so mod development sessions do not attempt to connect while Minecraft is closed). Toggle it on in Antigravity Customizations when running Minecraft, or copy the folder into `<workspace>/.agents/plugins/archica-mcp/` in any external building workspace (Antigravity also discovers `.agent/`, `_agents/`, and `_agent/` at the workspace root):

```text
<workspace>/
└── .agents/
    └── plugins/
        └── archica-mcp/
            ├── plugin.json
            ├── mcp_config.json
            └── skills/
                ├── archica-mcp-workflow/SKILL.md
                ├── archica-5x5-architecture/
                │   ├── SKILL.md
                │   └── references/
                ├── archica-terrain-survival/
                │   ├── SKILL.md
                │   └── references/
                └── archica-renovation-interior/SKILL.md
```

### Bundled Agent Skills

| Skill | Purpose |
|---|---|
| [`archica-mcp-workflow`](../.agents/plugins/archica-mcp/skills/archica-mcp-workflow/SKILL.md) | 11-tool inspect-draft-compile-iterate loop, forward-aligned local coordinates, auto-orientation rules, and error recovery. |
| [`archica-5x5-architecture`](../.agents/plugins/archica-mcp/skills/archica-5x5-architecture/SKILL.md) | Shared-Pillar 5x5 grid (`stride = 4`, `4N + 1` odd spans), 3-layer facade depth modes, `repeat_pattern` recipes, `gable_roof` infill, and material palettes. |
| [`archica-terrain-survival`](../.agents/plugins/archica-mcp/skills/archica-terrain-survival/SKILL.md) | Parsing `[TERRAIN_GRID]`, negative-Y slope/water foundations, and adapting builds to `[INVENTORY_MATERIALS]` and `survival_check`. |
| [`archica-renovation-interior`](../.agents/plugins/archica-mcp/skills/archica-renovation-interior/SKILL.md) | Decoding `scan_volume` RLE slices, snapping 5x5 extensions, framing `staircase` interiors, and weathering/upgrading via `replace_box`. |

> **Using standalone skills in other clients:** Each directory inside `skills/` follows the standard `SKILL.md` format and can be copied independently into `.agents/skills/` or another Agent-Skills-compatible MCP client.

### `plugin.json`

Create `<workspace>/.agents/plugins/archica-mcp/plugin.json`:

```json
{
  "name": "archica-mcp"
}
```

If you want the plugin **disabled by default** when opening the workspace and only enable it while Minecraft is running, add `"disabled": true`:

```json
{
  "name": "archica-mcp",
  "disabled": true
}
```

### `mcp_config.json`

Create `<workspace>/.agents/plugins/archica-mcp/mcp_config.json`:

```json
{
  "mcpServers": {
    "archica-mcp": {
      "url": "http://127.0.0.1:25580/mcp"
    }
  }
}
```

> **Schema & Transport Notes:**
> - **Do not add `"type": "streamable-http"`** — Antigravity uses `"url"` (legacy `"serverUrl"` is also accepted) for Streamable HTTP servers.
> - **Use `/mcp`, not `/sse`** — Antigravity does not support the legacy HTTP+SSE (`2024-11-05`) transport, and Archica MCP serves Streamable HTTP at `/mcp`.
> - **No global config needed** — you do not need to edit `~/.gemini/config/mcp_config.json`.

---

## 3. Sharing Across Selected Workspaces (Optional)

If you use Archica MCP across multiple building workspaces without registering it globally, store the `archica-mcp/` plugin folder once (for example, `~/mc-plugins/archica-mcp/`) and reference it from `<workspace>/.agents/plugins.json` in only the workspaces you choose:

```json
{
  "entries": [
    {
      "path": "~/mc-plugins",
      "include_only": ["archica-mcp"]
    }
  ]
}
```

- `~/` resolves relative to your home directory; relative paths resolve from the workspace root.
- Each directory in `entries` is scanned one level deep for plugin folders containing `plugin.json`.

---

## 4. Toggle & Verify in Antigravity

1. **Enable or disable on demand**: Open the Customizations page in Antigravity (or use the CLI `plugin enable` / `plugin disable` subcommands) to toggle `archica-mcp` live without restarting or editing global configs.
2. **Verify connected tools**: Open **Additional Options (...) > MCP Servers** in Antigravity to confirm `archica-mcp` is connected and exposes its 11 tools (`get_build_context`, `scan_volume`, `blueprint_create` through `blueprint_clear_placement`).
3. **Reference in chat**: In Antigravity 2.0, you can also type `@` in the chat canvas to mention `archica-mcp` or its tools directly.

---

## 5. Custom Port & In-Game Controls

If `25580` is already in use or you run multiple Minecraft instances, override the port via JVM argument or in-game client commands (no server operator permissions required):

- **Startup JVM property**: `-Darchica.mcp.port=<port>` (`1..65535`; non-numeric or out-of-range values log a warning and fall back to `25580`).
- **In-game `/archica` commands**:
  - `/archica status` — prints whether the server is running or stopped, the active loopback endpoint URL, in-memory draft count, and active `archica:` placements.
  - `/archica mcp start [port]` — starts or rebinds the embedded loopback server at runtime.
  - `/archica mcp stop` — stops the embedded MCP server cleanly.
  - `/archica clear <blueprint_id|all>` — removes active Litematica hologram placements from chat while keeping `.litematic` files on disk.

Whenever you change the listening port, update `"url"` in `.agents/plugins/archica-mcp/mcp_config.json` to match (for example, `http://127.0.0.1:25590/mcp`) and reconnect/reload the MCP server in Antigravity.

---

## 6. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `archica-mcp` does not show up in Antigravity | Standalone `.agents/mcp_config.json` without `plugin.json` | Ensure both `plugin.json` and `mcp_config.json` sit inside `.agents/plugins/archica-mcp/` at the workspace root (or referenced via `.agents/plugins.json`). |
| Connection refused / server failed to connect | Antigravity opened or plugin enabled before Minecraft started | Launch Minecraft first, check `/archica status` in-game, then toggle or reconnect `archica-mcp` in **MCP Servers**. |
| Tool returns `NO_WORLD_LOADED` | Minecraft is still on the title screen | Join a singleplayer or multiplayer world and retry the tool call. |
| Port conflict on startup | Another process or Minecraft client already bound `127.0.0.1:25580` | Archica MCP leaves the client running with a warning log; run `/archica mcp start 25590` (or `-Darchica.mcp.port=25590`) and update `mcp_config.json`. |
| HTTP request rejected (`Host` / `Origin` check) | Non-loopback host or LAN IP used in `"url"` | Archica MCP binds strictly to `127.0.0.1` with DNS-rebinding protection enabled; keep `"url"` set to `127.0.0.1` (or `localhost`). |

---

## Related Documentation

- [MCP Tools Reference](mcp-tools.md) — Schemas, parameters, and structured error codes for all 11 tools.
- [Architecture & Litematica Integration](architecture.md) — Embedded Ktor CIO transport, DNS-rebinding protection, and client-thread marshalling.
