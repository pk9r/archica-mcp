# Tasks

## 1. Antigravity Workspace-Plugin Setup Guide

- [x] 1.1 Create `docs/antigravity-setup.md` in English covering prerequisites and non-global workspace-scoped plugin setup under `<workspace>/.agents/plugins/archica-mcp/` (`plugin.json` with optional `"disabled": true`, and `mcp_config.json` using `"url": "http://127.0.0.1:25580/mcp"`, legacy `"serverUrl"` note, no `"type"` required, Streamable HTTP `/mcp` vs unsupported `/sse`), omitting global `~/.gemini/config/mcp_config.json`, and verify all endpoints, ports, and error codes match `openspec/specs/mcp-server/spec.md` and `openspec/specs/client-ux/spec.md`
- [x] 1.2 Document sharing across selected workspaces via `<workspace>/.agents/plugins.json`, live on/off toggling in Antigravity Customizations UI (or `plugin enable` / `plugin disable`), UI tool verification (`Additional Options (...) > MCP Servers` and `@` mention), custom port alignment (`-Darchica.mcp.port=<port>`, `/archica status`, `/archica mcp start [port]`), and troubleshooting (`NO_WORLD_LOADED`, port conflicts, loopback-only DNS-rebinding protection, missing `plugin.json` inside `.agents/plugins/<name>/`, reconnecting after Minecraft starts) in `docs/antigravity-setup.md`, and verify Markdown formatting and relative cross-links to `docs/mcp-tools.md` and `docs/architecture.md` resolve

## 2. README Integration

- [x] 2.1 Update `README.md` under `Connecting an MCP Client` to add a concise Antigravity workspace-plugin quick-start snippet (`.agents/plugins/archica-mcp/plugin.json` + `mcp_config.json`, without global config) and link to `docs/antigravity-setup.md`, and verify the JSON snippets are valid and match Antigravity's plugin and Streamable HTTP schemas
- [x] 2.2 Add `docs/antigravity-setup.md` to the `Documentation` section of `README.md` and verify all Markdown links in `README.md` point to existing files
