# Design

## Context

See [proposal.md](proposal.md) for motivation. Currently, `README.md` provides a generic `mcpServers` JSON snippet (`"type": "streamable-http"` + `"url"`) and links to four technical docs under `docs/` (`architecture.md`, `spatial-context.md`, `blueprint-primitives.md`, `mcp-tools.md`). In Antigravity's customization system, standalone `mcp_config.json` is global-only (`~/.gemini/config/mcp_config.json`), whereas workspace-scoped or toggleable MCP configurations are packaged as plugins inside `.agents/plugins/<plugin_name>/`.

## Goals / Non-Goals

**Goals:**
- Keep `README.md` concise while giving Antigravity users an immediate workspace-scoped plugin snippet and link to the full guide.
- Document non-global Antigravity configuration (`<workspace>/.agents/plugins/archica-mcp/` and `<workspace>/.agents/plugins.json`), on-demand enable/disable (`"disabled": true` + Customizations UI toggle), UI verification, custom port alignment, and troubleshooting in `docs/antigravity-setup.md` (in English).
- Align all documented endpoints, ports, JVM flags, in-game `/archica` commands, and error codes (`NO_WORLD_LOADED`, DNS-rebinding loopback rules) with the implemented `mcp-server` and `client-ux` specs.

**Non-Goals:**
- Documenting or recommending global `~/.gemini/config/mcp_config.json` registration.
- Changing embedded MCP server transport, port defaults, or tool schemas.
- Committing an active `.agents/plugins/archica-mcp/` directory into this mod repository itself (this repo only documents how users configure it in their build workspaces).
- Translating existing repository documentation away from English.

## Decisions

1. **Workspace-scoped plugin (`.agents/plugins/archica-mcp/`) instead of global `mcp_config.json`**
   - *Rationale*: Antigravity does not auto-discover a standalone `.agents/mcp_config.json`. Wrapping `mcp_config.json` inside `.agents/plugins/archica-mcp/` with `plugin.json` keeps Archica MCP strictly scoped to the workspace(s) where the user wants Minecraft building tools and avoids polluting unrelated projects.
   - *Toggleability*: Document `"disabled": true` in `plugin.json` as an optional setting so users can keep the plugin off by default and toggle it live in Antigravity Customizations UI (or via `plugin enable` / `plugin disable`) only when Minecraft is running.
   - *Multi-workspace sharing without global*: Document `.agents/plugins.json` (`entries` pointing to a shared local directory) for users who build across multiple workspaces without registering globally.

2. **Split concise README snippet from dedicated `docs/antigravity-setup.md`**
   - *Rationale*: Keeps `README.md` scannable for users of other MCP clients while giving Antigravity users the full two-file plugin layout (`plugin.json` + `mcp_config.json`), `.agents/plugins.json` option, and troubleshooting without cluttering the README.
   - *Schema choice*: Use `{"mcpServers": {"archica-mcp": {"url": "http://127.0.0.1:25580/mcp"}}}` without `"type"`, matching Antigravity's `mcp_config.json` Streamable HTTP schema, while noting legacy `"serverUrl"` compatibility and warning against `/sse`.

3. **Standardize `docs/antigravity-setup.md` structure on existing `docs/*.md` conventions**
   - *Rationale*: Start with a short `> **Status:**` summary banner, ASCII workspace-plugin connection diagram, numbered sections (Prerequisites, Workspace Plugin Setup, Optional Sharing via `plugins.json`, Toggle & Verify in Antigravity, Custom Port & In-Game Controls, Troubleshooting), and cross-links to `docs/mcp-tools.md` and `docs/architecture.md`.

## Risks / Trade-offs

- **Users placing a bare `.agents/mcp_config.json` without `plugin.json`** → Explicitly call out in the setup steps and Troubleshooting that Antigravity requires both `plugin.json` and `mcp_config.json` inside `.agents/plugins/<plugin_name>/`.
- **Antigravity UI labels may evolve across releases** → Anchor instructions on the `.agents/plugins/archica-mcp/` file layout and mention UI inspection (`Additional Options (...) > MCP Servers` / Customizations / `@` mention) as verification helpers.
- **Users launching Antigravity or enabling the plugin before Minecraft** → Explicitly document in Troubleshooting that Minecraft must be running first (or MCP reconnected after launch) and a world must be loaded for world/placement tools (`NO_WORLD_LOADED`).
