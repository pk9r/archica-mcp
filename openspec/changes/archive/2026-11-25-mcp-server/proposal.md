# Proposal

## Why

The `world-context-compression` spatial scanning core and `ClientThreadDispatcher` already exist in the codebase, but AI agents cannot invoke them yet because the embedded MCP server is still a logging-only stub in `ArchicaMcpClient`. Standing up the embedded Streamable HTTP MCP server and wiring the two spatial context tools first creates an immediately testable end-to-end client-to-Minecraft loop before adding blueprint and Litematica capabilities.

## What Changes

- Introduce the `mcp-server` capability for hosting an embedded Streamable HTTP MCP endpoint inside the Minecraft client JVM and exposing the first vertical slice of MCP tools:
  - **Loopback-only embedded server & security:** Host Ktor CIO (`3.5.2`) + official MCP Kotlin SDK (`0.16.0`) at `http://127.0.0.1:<port>/mcp` (default `25580`, overridable via `-Darchica.mcp.port`) with `enableDnsRebindingProtection = true` so only local loopback origins/hosts (`127.0.0.1`, `localhost`, `[::1]`) are accepted.
  - **Fail-silent client lifecycle:** Start the server asynchronously during `ClientModInitializer.onInitializeClient()` and shut it down gracefully on `ClientLifecycleEvents.CLIENT_STOPPING`. If port binding or server startup fails (e.g., port already in use by another Minecraft instance), log a warning and continue running the game without crashing.
  - **Initial tool slice (spatial context):** Register `get_build_context` and `scan_volume` backed by `ClientThreadDispatcher` (preserving their existing compact text payloads). Blueprint session and Litematica tools are explicitly deferred to follow-up changes.
  - **Unified structured error format:** Standardize all MCP tool errors to return `isError = true` with JSON `{"error": {"code": "<CODE>", "message": "<MESSAGE>"}}`, mapping `ContextErrorCode` (`NO_WORLD_LOADED`, `INVALID_RADIUS`, `INVALID_BOUNDS`, `VOLUME_TOO_LARGE`) alongside transport/dispatch errors (`INVALID_ARGUMENTS`, `CLIENT_TIMEOUT`, `INTERNAL_ERROR`).

## Capabilities

### New Capabilities

- `mcp-server`: Embedded loopback-only Streamable HTTP MCP server lifecycle, DNS-rebinding protection, port configuration (`-Darchica.mcp.port`), unified JSON error payload envelope, and registration of the spatial context tools (`get_build_context`, `scan_volume`).

### Modified Capabilities

<!-- None: `world-context-compression` scanner requirements and output formats are unchanged. -->

## Impact

- **Pure Kotlin MCP core (`src/main/kotlin/com/archica/mcp/server/`):** MCP server factory, tool input schema definitions, argument parsing/validation, `CallToolResult` formatting, and unified error mapper (`McpErrorCode` / JSON error envelope) testable without booting Minecraft.
- **Fabric client integration (`src/client/kotlin/com/archica/mcp/`):** Ktor CIO embedded engine lifecycle wired into `ArchicaMcpClient` + `ClientLifecycleEvents.CLIENT_STOPPING`, port resolution (`-Darchica.mcp.port`), fail-silent startup guard, and `ClientThreadDispatcher` tool handlers.
- **Unit & integration tests (`src/test/kotlin/com/archica/mcp/server/`):** JUnit 5 tests covering argument validation, unified error JSON payloads, `get_build_context` / `scan_volume` MCP invocation against `FakeWorldContextSource`, loopback/DNS-rebinding protection, and fail-silent port conflict handling.
