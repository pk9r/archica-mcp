# Tasks

## 1. Core Rasterizer & Litematica Bridge Hardening

- [x] 1.1 Add horizontal `CONNECTABLE` (`north`, `south`, `east`, `west`) auto-connection pass after sequential op evaluation in `BlueprintRasterizer.kt` and horizontal directional property rotation in `BlueprintTransform.kt`, preserving explicit caller properties; verify with JUnit 5 tests in `BlueprintRasterizerTest.kt` and `BlueprintModelsAndTransformTest.kt` via `./gradlew test`.
- [x] 1.2 Make `LitematicaBridge.listActivePlacements()` (or `hasActivePlacement`) `suspend`, update `BlueprintService.list()` in `BlueprintSessionStore.kt` to await it, and update `ClientLitematicaBridge.kt` to query `SchematicPlacementManager` via `ClientThreadDispatcher` (returning empty when no world is loaded) and evict matching `SchematicHolder` entries before deleting files in `clearPlacement(deleteFile = true)`; verify with `FakeLitematicaBridge` unit tests in `BlueprintCompilerServiceTest.kt` and `./gradlew test compileClientKotlin`.

## 2. LLM-Ergonomic MCP Metadata & Runtime Server Controller

- [x] 2.1 Add concise MCP `instructions` to `ArchicaMcpServerFactory.create` and enrich `blueprint_add_ops` / `blueprint_update_op` JSON input schemas with explicit descriptions of all 11 primitive variants and their required/optional parameters in `ArchicaMcpServer.kt`, plus update `docs/mcp-tools.md` if needed; verify with Streamable HTTP `initialize` and `tools/list` integration tests in `ArchicaMcpServerIntegrationTest.kt` via `./gradlew test`.
- [x] 2.2 Introduce a thread-safe runtime MCP server lifecycle controller (`status`, `start(port)`, `stop`) reusable by both `ArchicaMcpClient.kt` and client commands, preserving `127.0.0.1` loopback binding, DNS-rebinding protection, and fail-silent conflict handling; verify restart and port-rebind behavior in `McpServerCoreTest.kt` / `ArchicaMcpServerIntegrationTest.kt` via `./gradlew test`.

## 3. Client UX (`/archica` Commands, Feedback & README Sync)

- [x] 3.1 Implement `/archica status`, `/archica mcp start [port]`, `/archica mcp stop`, and `/archica clear <blueprint_id|all>` in `src/client/kotlin/com/archica/mcp/command/ArchicaClientCommands.kt` using `ClientCommandRegistrationCallback` and `ClientCommands`, wire registration and placement activate/replace/clear chat feedback hooks in `ArchicaMcpClient.kt` / `ClientLitematicaBridge.kt`, and verify with `./gradlew compileClientKotlin`.
- [x] 3.2 Update `README.md` (and `docs/architecture.md` / `docs/blueprint-primitives.md`) to replace outdated "Design & Documentation Phase" status with accurate v1 setup, `/archica` command usage, and `CONNECTABLE` auto-orientation behavior; verify links and commands match implemented code.

## 4. End-to-End Build & Live Minecraft Verification

- [x] 4.1 Run `./gradlew test build` to confirm all unit/integration tests and Fabric Jar-in-Jar packaging (`build/libs/archica-mcp-0.1.0.jar`) succeed cleanly.
- [x] 4.2 Load the built `build/libs/archica-mcp-0.1.0.jar` in the user's Fabric `26.3` launcher (with Fabric API, Fabric Language Kotlin, MaLiLib `0.30.2`, and Litematica `0.29.1`), verify `/archica status` and `/archica mcp` commands in-game, connect an MCP client to `http://127.0.0.1:25580/mcp` to execute `get_build_context` -> `blueprint_create` -> `blueprint_add_ops` -> `blueprint_compile` -> `blueprint_clear_placement`, and confirm the hologram renders, updates in place, notifies chat, and clears cleanly.

## Workflow follow-up

- Archive the `archica-mcp-core` change after all tasks and live verification checks pass.
- Verify the archived specs with `openspec list --specs` and `openspec validate --all`.
