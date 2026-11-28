# Proposal

## Why

`BlueprintSessionStore` currently keeps all draft sessions (`origin`, `facing`, `ops`, `nextOpNumber`) exclusively in an in-memory `ConcurrentHashMap`. Restarting Minecraft drops every in-progress blueprint session even though compiled `.litematic` files remain on disk, and upcoming server-side shared blueprints (Model X) require a portable, environment-independent draft persistence layer in `src/main` so sessions survive restarts on both client and server.

## What Changes

- Introduce a pure-Kotlin JSON draft persistence store (`BlueprintDraftStore`) in `src/main` that atomically persists each blueprint session as `<blueprint_id>.draft.json` alongside `.litematic` files under `<schematicsBaseDir>/archica/`.
- Wire `BlueprintSessionStore` and `BlueprintService` to persist session creation (`create`), mutations (`addOps`, `updateOp`, `removeOp`, `undo`), and overwrites, while preserving resilient in-memory operation when no persistence directory is configured.
- Load valid `.draft.json` sidecar files back into `BlueprintSessionStore` on initialization (logging and skipping malformed or unreadable files without blocking startup), and allow client initialization (`ArchicaMcpClient`) to resolve the schematics directory safely without requiring Litematica to be active in a world.
- Record lightweight placement metadata (`lastCompiledAt`, `lastWorldOrigin`, `activated`) in the sidecar alongside the lossless AST (`origin`, `facing`, `nextOpNumber`, `ops`) so future server-sync and re-activation flows can reconstruct placement state across restarts.

## Capabilities

### New Capabilities

<!-- None: draft persistence extends the existing blueprint session lifecycle capability. -->

### Modified Capabilities

- `blueprint-primitives`: Extend the stateful blueprint draft session lifecycle requirement so draft sessions, monotonic `op_id` counters, and ordered primitive operations persist atomically to `.draft.json` sidecar files and reload automatically across restarts.

## Impact

- **Common Core (`src/main`)**: `BlueprintModels.kt` / `BlueprintSessionStore.kt` (serialization DTOs, persistence hooks, reload API), new `BlueprintDraftStore` in `com.archica.mcp.blueprint`, and `LitematicSchematicCompiler.kt` directory constants reuse.
- **Client Entrypoint (`src/client`)**: `ArchicaMcpClient.kt` wires the resolved `schematics/archica` directory into `BlueprintService` on client startup and loads persisted drafts before starting the embedded MCP server.
- **Dependencies**: Uses existing `kotlinx.serialization.json` and `java.nio.file` APIs already bundled in the project; no new Gradle or Modrinth dependencies required.
- **Compatibility**: Additive only; existing MCP tool schemas and `.litematic` binary outputs remain unchanged.
