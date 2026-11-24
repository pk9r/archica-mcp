# Tasks

## 1. Pure-Kotlin .litematic NBT Compiler

- [x] 1.1 Implement pure-Kotlin binary NBT writer (`TagCompound`, `TagList`, `TagLongArray`, `TagInt`, `TagLong`, `TagString`) over GZIP and `LitematicaBitArray` packer (`bits >= 2`, 64-bit `long`-straddling packing) in `src/main/kotlin/com/archica/mcp/litematica/`, and verify with JUnit 5 round-trip NBT decode tests covering 2-bit, 3-bit, and 4-bit boundary packing.
- [x] 1.2 Implement `LitematicSchematicCompiler` in `src/main/kotlin/com/archica/mcp/litematica/` to transform `VoxelGrid` + `BlockSpec` by `blueprint.facing`, normalize coordinates relative to `worldBounds.min` (`[0..size-1]`, `Position = [0, 0, 0]`), populate `Metadata` and single-subregion `Regions`, reject empty (`totalBlocks == 0`) blueprints with `INVALID_ARGUMENTS`, and write atomically (temp file -> move) to `<schematicsBaseDir>/archica/<blueprint_id>.litematic`; verify with unit tests covering facing rotation, negative local overhangs, empty-grid rejection, and decoded voxel accuracy.

## 2. LitematicaBridge Port & Blueprint Compilation Service

- [x] 2.1 Define `LitematicaBridge` port (`schematicsDirectory`, `activatePlacement`, `clearPlacement`, `hasActivePlacement`, `LitematicaUnavailableException`) and extend `BlueprintService` in `src/main/kotlin/com/archica/mcp/blueprint/BlueprintSessionStore.kt` with `compile(blueprintId, activate, originOverride)` and `clearPlacement(blueprintId, deleteFile)` (supporting `activate = false` when no world is loaded, preserving `schematic_path` on adapter error, and keeping `blueprint_list` active placement status accurate); verify with unit tests using a fake `LitematicaBridge` and `@TempDir`.
- [x] 2.2 Implement `ClientLitematicaBridge` in `src/client/kotlin/com/archica/mcp/litematica/` marshalled via `ClientThreadDispatcher` (`NO_WORLD_LOADED` guard) using `DataManager.getSchematicsBaseDirectory()`, `SchematicHolder` cache eviction, `LitematicaSchematic.createFromFile`, `SchematicPlacement.createFor(..., "archica:<blueprint_id>", true, true)`, and `SchematicPlacementManager` (`removeSchematicPlacement` / `addSchematicPlacement`), plus `SharedConstants` `MinecraftDataVersion` lookup; wire `ClientLitematicaBridge` into `ArchicaMcpClient.kt` and verify compilation with `./gradlew compileClientKotlin`.

## 3. MCP Server Tool Exposure & Documentation

- [x] 3.1 Register `blueprint_compile` and `blueprint_clear_placement` in `ArchicaMcpServer.kt`, add `BlueprintToolCodec` request parsers and response formatters, and map `LITEMATICA_UNAVAILABLE` in `McpErrorCode` (`McpServerModels.kt`); verify with Streamable HTTP integration tests in `ArchicaMcpServerIntegrationTest.kt` covering compile, re-compile replacement, clear placement (`delete_file` true/false), empty blueprint rejection, and `LITEMATICA_UNAVAILABLE` error envelopes.
- [x] 3.2 Update `docs/architecture.md`, `docs/blueprint-primitives.md`, and `docs/mcp-tools.md` to reflect implemented `blueprint_compile` / `blueprint_clear_placement` status, `world_origin` (`worldBounds.min`) behavior, and `LitematicaBridge` details; verify docs match implemented tool schemas and error codes.

## 4. End-to-End Verification

- [x] 4.1 Run `./gradlew test compileClientKotlin` and confirm all unit tests, MCP integration tests, and Fabric client Kotlin sources pass and compile cleanly.
