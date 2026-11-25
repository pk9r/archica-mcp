# Proposal

## Why

`README.md` currently shows only a generic MCP client snippet (`"type": "streamable-http"`) without Antigravity-specific configuration guidance. Because Antigravity's standalone `mcp_config.json` is global-only, users who do not want Archica MCP loaded across every unrelated project need a clear, workspace-scoped plugin setup guide (`plugin.json` + `mcp_config.json` under `.agents/plugins/`).

## What Changes

- Create `docs/antigravity-setup.md` (in English, matching existing repository documentation) covering non-global Antigravity setup:
  - Prerequisites (Minecraft `26.3`, Fabric, Litematica, and `archica-mcp` running)
  - Workspace-scoped plugin configuration under `<workspace>/.agents/plugins/archica-mcp/` (`plugin.json` with optional `"disabled": true` for on-demand toggling, and `mcp_config.json` using `"url": "http://127.0.0.1:25580/mcp"`, legacy `"serverUrl"` note, and Streamable HTTP `/mcp` vs unsupported `/sse`)
  - Sharing across selected workspaces without global registration via `<workspace>/.agents/plugins.json`
  - Enabling/disabling the plugin live in Antigravity Customizations UI (or CLI `plugin enable` / `plugin disable`) and verifying discovered tools (`Additional Options (...) > MCP Servers`, `@` mention)
  - Aligning custom ports (`-Darchica.mcp.port=<port>`, `/archica status`, `/archica mcp start [port]`)
  - Troubleshooting common issues (`NO_WORLD_LOADED`, port conflicts, loopback-only DNS-rebinding protection, plugin discovery layout, and reconnecting after launching Minecraft)
- Update `README.md` to add a concise Antigravity workspace-plugin quick-start snippet under `Connecting an MCP Client` and link `docs/antigravity-setup.md` in both `Connecting an MCP Client` and `Documentation` (omitting global `~/.gemini/config/mcp_config.json`).

## Capabilities

### New Capabilities
<!-- None: pure documentation change with no new system behavior. -->

### Modified Capabilities
<!-- None: existing MCP server and client UX requirements remain unchanged. -->

## Impact

- **Documentation**: `README.md` (modified), `docs/antigravity-setup.md` (new).
- **Code / Runtime**: None — no changes to mod source code, MCP tool schemas, transport, or tests.
- **OpenSpec Specs**: Skipped (`skip_specs: true` in `.openspec.yaml`) because no observable system behavior changes.
