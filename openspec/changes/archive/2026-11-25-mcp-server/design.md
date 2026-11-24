# Design

## Context

See [proposal.md](proposal.md) for motivation and scope, and [specs/mcp-server/spec.md](specs/mcp-server/spec.md) for the externally observable contracts.

Currently:
- [build.gradle.kts](../../build.gradle.kts) and [gradle.properties](../../gradle.properties) already include and Jar-in-Jar bundle `io.modelcontextprotocol:kotlin-sdk-server:0.16.0` and `io.ktor:ktor-server-cio-jvm:3.5.2`.
- [ArchicaModInfo.kt](../../src/main/kotlin/com/archica/mcp/ArchicaModInfo.kt) defines `DEFAULT_MCP_HOST = "127.0.0.1"`, `DEFAULT_MCP_PORT = 25580`, and `DEFAULT_MCP_PATH = "/mcp"`.
- [ClientThreadDispatcher.kt](../../src/client/kotlin/com/archica/mcp/context/ClientThreadDispatcher.kt) already marshals `getBuildContext` and `scanVolume` onto the `Minecraft` client thread with a `5_000L` ms `withTimeout(...)` and `NO_WORLD_LOADED` guard.
- [ArchicaMcpClient.kt](../../src/client/kotlin/com/archica/mcp/ArchicaMcpClient.kt) only logs the planned endpoint and has no server startup/shutdown logic yet.

## Goals / Non-Goals

**Goals:**
- Keep MCP tool definitions, JSON argument parsing, error mapping, and `Server` configuration in `src/main/kotlin/com/archica/mcp/server/` so `./gradlew test` can exercise the real MCP protocol + Ktor CIO server end-to-end against `FakeWorldContextSource` without launching Minecraft.
- Keep Fabric lifecycle wiring (`ClientModInitializer`, `ClientLifecycleEvents.CLIENT_STOPPING`, `ClientThreadDispatcher`) thin in `src/client/kotlin/com/archica/mcp/`.
- Guarantee fail-silent startup so port conflicts never crash the Minecraft client.

**Non-Goals:**
- Registering blueprint session or Litematica placement tools (deferred until `blueprint-primitives` and `litematica-compiler` exist).
- Adding `/archica mcp start|stop|status` chat commands or MaLiLib configuration GUI screens in this slice (only `-Darchica.mcp.port` override is included now).

## Decisions

### 1. Pure-Kotlin MCP Server Core with Injected Suspend Tool Backend
- **Decision:** Define a `SpatialContextService` interface (or suspend handler callbacks) in `src/main/kotlin/com/archica/mcp/server/` with `getBuildContext(BuildContextRequest): BuildContextResult` and `scanVolume(ScanVolumeRequest): ScanVolumeResult`. Build the MCP `Server` and Ktor `embeddedServer(CIO, ...)` factory around that abstraction in `src/main/kotlin/`, while `src/client/kotlin/` simply adapts `ClientThreadDispatcher` into `SpatialContextService`.
- **Rationale:** Matches the `WorldContextSource` pattern from `world-context-compression` and Loom's `splitEnvironmentSourceSets()`. Unit tests in `src/test/kotlin/` can start the real Ktor CIO + MCP SDK endpoint on an ephemeral port (`port = 0` or test port) backed by `FakeWorldContextSource` and verify HTTP/JSON-RPC/DNS-rebinding behavior directly.
- **Alternative considered:** Constructing the MCP `Server` and calling `ClientThreadDispatcher` directly inside `ArchicaMcpClient`. Rejected because `src/test` cannot access `client` source set classes (`Minecraft`, `ClientThreadDispatcher`).

### 2. Ktor CIO Loopback Hosting + SDK DNS-Rebinding Protection
- **Decision:** Configure `embeddedServer(CIO, host = ArchicaModInfo.DEFAULT_MCP_HOST, port = resolvedPort)` with Ktor `mcpStreamableHttp(path = ArchicaModInfo.DEFAULT_MCP_PATH)` (keeping `enableDnsRebindingProtection = true` on the SDK endpoint).
- **Rationale:** Binding `127.0.0.1` prevents non-local network access at the socket layer, while `enableDnsRebindingProtection = true` blocks malicious browser pages on `localhost` / `127.0.0.1` from invoking local tools via crafted `Host`/`Origin` headers.
- **Alternative considered:** Using SSE-only transport or disabling DNS-rebinding protection for convenience. Rejected because Streamable HTTP is the target v1 transport in `docs/architecture.md` and disabling DNS-rebinding weakens local security.

### 3. Port Resolution (`-Darchica.mcp.port`) and Fail-Silent Lifecycle
- **Decision:** Resolve port via a pure helper reading `System.getProperty("archica.mcp.port")`: accept `1..65535`, otherwise log a warning and return `25580`. In `ArchicaMcpClient.onInitializeClient()`, launch `embeddedServer.start(wait = false)` on a dedicated `CoroutineScope(SupervisorJob() + Dispatchers.IO)` inside a `try/catch (t: Throwable)` block; on failure, log `WARN` with the attempted host/port and clear the server handle. Register `ClientLifecycleEvents.CLIENT_STOPPING` to call `stop(500, 2_000)` and cancel the server scope.
- **Rationale:** Launching with `wait = false` on IO avoids blocking Fabric client startup or frame rendering, and catching `BindException` / startup failures prevents multi-instance players from crashing on launch.
- **Alternative considered:** Auto-incrementing ports (`25580`, `25581`, ...) on conflict. Rejected because MCP client configs (IDE / Claude / Antigravity) point to a fixed URL and silent port hopping causes clients to connect to the wrong Minecraft instance; explicit `-Darchica.mcp.port` + fail-silent warning is predictable.

### 4. Strict Argument Parsing & Unified JSON Error Envelope
- **Decision:** Parse MCP tool `arguments` (`JsonObject?`) manually with explicit type/bounds checks (instead of letting uncaught deserialization exceptions bubble out as generic SDK errors) and wrap every tool handler in a shared `executeTool { ... }` helper returning `CallToolResult` with `isError = true` and `TextContent(Json.encodeToString(McpErrorEnvelope(McpErrorDetail(code, message))))`.
  - Map `ContextCompressionException` -> `error.code = e.code.name`.
  - Map argument validation errors -> `INVALID_ARGUMENTS`.
  - Map `TimeoutCancellationException` (from `ClientThreadDispatcher`) -> `CLIENT_TIMEOUT`.
  - Map any other `Throwable` (re-throwing `CancellationException` only when the server itself is cancelling, not timeout) -> `INTERNAL_ERROR`.
- **Rationale:** Guarantees a single machine-readable JSON error structure across all tools, prevents raw stack traces in LLM context, and reuses `ContextErrorCode` directly without duplicating validation logic. Note: in Kotlin coroutines, `TimeoutCancellationException` is a subclass of `CancellationException`, so `TimeoutCancellationException` MUST be caught before generic `CancellationException` handling!

## Risks / Trade-offs

- **[Risk] `TimeoutCancellationException` extends `CancellationException`** -> **Mitigation:** Explicitly catch `TimeoutCancellationException` before `CancellationException` in the unified error wrapper so main-thread timeouts emit `CLIENT_TIMEOUT` instead of cancelling the Ktor request coroutine silently.
- **[Risk] Kotlin MCP SDK `0.16.0` `mcpStreamableHttp` API nuances** -> **Mitigation:** Verify exact `0.16.0` Ktor plugin/routing signatures (`Server`, `Implementation`, `Tool`, `CallToolResult`, `mcpStreamableHttp`) during compilation and write an in-JVM Ktor test covering handshake + `tools/list` + `tools/call`.
- **[Risk] Multi-instance port collision hides which instance owns `25580`** -> **Mitigation:** Log clear `INFO` on successful bind (`http://127.0.0.1:<port>/mcp`) and clear `WARN` on bind failure mentioning `-Darchica.mcp.port`.
