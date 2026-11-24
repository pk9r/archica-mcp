# Tasks

## 1. Pure-Kotlin MCP Core, Argument Validation & Unified Error Envelope

- [x] 1.1 Define `SpatialContextService`, `McpPortConfig` (`-Darchica.mcp.port` parsing for `1..65535` with warning fallback to `25580`), and `McpErrorCode` / JSON error envelope serialization (`{"error": {"code": ..., "message": ...}}`) in `src/main/kotlin/com/archica/mcp/server/`, plus unit tests verifying port parsing and error serialization via `./gradlew test`
- [x] 1.2 Implement strict JSON argument parsing (`get_build_context` optional `radius`; `scan_volume` required 3-int `min`/`max` + optional `include_states`) and shared `executeTool` exception mapping (`ContextCompressionException` -> matching code, malformed args -> `INVALID_ARGUMENTS`, `TimeoutCancellationException` -> `CLIENT_TIMEOUT`, fallback -> `INTERNAL_ERROR`), verified by unit tests in `src/test/kotlin/com/archica/mcp/server/` via `./gradlew test`

## 2. Embedded Ktor CIO Streamable HTTP Server

- [x] 2.1 Implement the MCP `Server` factory and Ktor CIO `embeddedServer` setup binding `127.0.0.1` at `/mcp` via `mcpStreamableHttp` with `enableDnsRebindingProtection = true`, registering `get_build_context` and `scan_volume` schemas and handlers in `src/main/kotlin/com/archica/mcp/server/`, verified by `./gradlew compileKotlin`
- [x] 2.2 Add end-to-end Ktor/MCP server tests in `src/test/kotlin/com/archica/mcp/server/` against `FakeWorldContextSource` verifying `tools/list`, `get_build_context` and `scan_volume` execution, unified JSON error payloads, rejection of external `Origin` headers, and fail-silent handling on port bind conflict via `./gradlew test`

## 3. Fabric Client Lifecycle & Documentation Sync

- [x] 3.1 Wire `ArchicaMcpClient` (`src/client/kotlin/com/archica/mcp/ArchicaMcpClient.kt`) to adapt `ClientThreadDispatcher` into `SpatialContextService`, start the embedded server asynchronously on a `SupervisorJob() + Dispatchers.IO` scope with fail-silent `WARN` logging on startup/bind failure, and stop it cleanly via `ClientLifecycleEvents.CLIENT_STOPPING`, verified by `./gradlew compileClientKotlin`
- [x] 3.2 Update `docs/architecture.md` and `docs/mcp-tools.md` to document the v1 `mcp-server` slice (`-Darchica.mcp.port`, fail-silent bind behavior, and unified `INVALID_ARGUMENTS` / `CLIENT_TIMEOUT` / `INTERNAL_ERROR` codes) and verify the full project builds and passes tests via `./gradlew build`
