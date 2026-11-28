# Tasks

## 1. Serializable Draft Store (`src/main`)

- [x] 1.1 Define versioned `@Serializable` draft persistence DTOs (`PersistedBlueprintDraft`, `PersistedBlueprintOp`, `PersistedPlacementState`, and all 11 `PrimitiveOp` variants) plus bidirectional mappers in `com.archica.mcp.blueprint` (`BlueprintDraftPersistence.kt`), and verify round-trip serialization unit tests pass via `./gradlew test --tests "com.archica.mcp.blueprint.BlueprintDraftStoreTest"`.
- [x] 1.2 Implement `BlueprintDraftStore(draftsDir: Path)` with atomic temp-file + move writes (`<blueprint_id>.draft.json`), `loadAll()` scanning with `blueprint_id`/filename/schema validation, and `WARN` logging that skips corrupted files, verified by unit tests covering atomic save, reload, and malformed JSON isolation in `./gradlew test --tests "com.archica.mcp.blueprint.BlueprintDraftStoreTest"`.

## 2. Session Store & Service Integration

- [x] 2.1 Extend `BlueprintSessionStore` and `BlueprintService` (`src/main/kotlin/com/archica/mcp/blueprint/BlueprintSessionStore.kt`) to accept an optional `BlueprintDraftStore? = null`, persist state on `create`, `addOps`, `updateOp`, `removeOp`, `undo`, `compile`, and `clearPlacement` (keeping `.draft.json` intact when `delete_file = true`), and expose `loadPersistedDrafts()` with monotonic `nextOpNumber` clamping and `BlockCatalog` validation, verified by unit tests in `BlueprintSessionStoreTest` and `BlueprintCompilerServiceTest`.
- [x] 2.2 Verify MCP tool round-trips across simulated service restarts (creating/mutating a blueprint, restarting `BlueprintService` against the same temp directory, asserting `blueprint_list`, `blueprint_inspect`, monotonic `op_id` continuation, and `blueprint_compile` behavior) in `ArchicaMcpServerIntegrationTest` via `./gradlew test`.

## 3. Client Startup Wiring & End-to-End Verification

- [x] 3.1 Wire `BlueprintDraftStore` into `ArchicaMcpClient.onInitializeClient()` (`src/client/kotlin/com/archica/mcp/ArchicaMcpClient.kt`) resolving `<schematicsBaseDir>/archica` (with `FabricLoader.getInstance().gameDir.resolve("schematics/archica")` fallback) and invoking `loadPersistedDrafts()` before async MCP server startup, verified by `./gradlew test build`.
- [x] 3.2 Run full verification (`./gradlew test build` and `openspec validate draft-persistence`) and confirm all unit/integration tests and spec checks pass cleanly.
