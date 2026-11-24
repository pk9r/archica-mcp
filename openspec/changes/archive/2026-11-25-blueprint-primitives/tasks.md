# Tasks

## 1. Pure-Kotlin Blueprint Models, BlockCatalog & Facing Transforms

- [x] 1.1 Define core blueprint domain models (`BlueprintSession`, `BlueprintErrorCode` / `BlueprintException`, `PrimitiveOp` sealed hierarchy for all 11 v1 primitives, `BlockSpec`, `BlockCatalog` port, `BlockKind`) and bracketed blockstate parser in `src/main/kotlin/com/archica/mcp/blueprint/`, plus unit tests in `src/test/kotlin/com/archica/mcp/blueprint/` via `./gradlew test`
- [x] 1.2 Implement forward-aligned `BlueprintTransform` (`NORTH`/`SOUTH`/`EAST`/`WEST` coordinate rotation around `(0, 0, 0)` and directional `facing`/`axis` blockstate property rotation) and blockstate auto-orientation helpers (`stairs`, `logs`/pillars, `doors`), verified by unit tests via `./gradlew test`

## 2. Sequential Primitive Rasterizers & Session Service

- [x] 2.1 Implement `VoxelGrid` (enclosing bounding box, `MAX_RASTERIZED_VOLUME = 131_072` `VOLUME_TOO_LARGE` guard, material summary) and rasterizers for `set_block`, `fill_box`, `hollow_box`, `line`, `replace_box`, `clear_box`, and bounded `repeat_pattern` (`count in 1..256`, `depth <= 4`), verified by unit tests via `./gradlew test`
- [x] 2.2 Implement geometric/architectural rasterizers (`cylinder`, `dome`, `gable_roof`, `staircase`) with auto-orientation and `fill_under` support, verified by unit tests covering odd/even roof spans, overhang, small radii, and hollow shells via `./gradlew test`
- [x] 2.3 Implement thread-safe `BlueprintSessionStore` (monotonic `op_1`, `op_2` IDs, atomic batch validation, in-place `update_op`, `remove_op`, `undo`, `inspect`, `list`) and `BlueprintService` with non-blocking optional `survival_check`, verified by unit tests against `FakeBlockCatalog` via `./gradlew test`

## 3. MCP Tool Exposure & Error Mapping

- [x] 3.1 Extend `McpErrorCode` and `McpToolExecutor` (`BLUEPRINT_EXISTS`, `BLUEPRINT_NOT_FOUND`, `OP_NOT_FOUND`, `INVALID_BLOCK_ID`), add JSON schemas and strict argument parsers for `blueprint_create`, `blueprint_add_ops`, `blueprint_update_op`, `blueprint_remove_op`, `blueprint_undo`, `blueprint_inspect`, and `blueprint_list` in `src/main/kotlin/com/archica/mcp/server/`, verified by `./gradlew compileKotlin`
- [x] 3.2 Add end-to-end Streamable HTTP MCP tests in `src/test/kotlin/com/archica/mcp/server/` covering `tools/list`, full draft session lifecycle over MCP, survival warning payloads, and structured error envelopes via `./gradlew test`

## 4. Client BlockCatalog Adapter, Survival Check & Documentation Sync

- [x] 4.1 Implement `ClientBlockCatalog` (`BuiltInRegistries.BLOCK` + `StateDefinition` property validation & `BlockKind` classification) and optional `InventorySnapshotProvider` via `ClientThreadDispatcher`, and wire `BlueprintService` into `ArchicaMcpClient` (`src/client/kotlin/com/archica/mcp/`), verified by `./gradlew compileClientKotlin`
- [x] 4.2 Update `docs/blueprint-primitives.md` and `docs/mcp-tools.md` to match finalized facing transforms, rasterization geometry/caps, and implemented blueprint draft tools, and verify the full build via `./gradlew build`
