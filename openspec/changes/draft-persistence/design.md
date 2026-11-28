# Design

## Context

`BlueprintSessionStore` and `BlueprintService` currently live in `src/main` (`com.archica.mcp.blueprint`) and maintain draft sessions strictly in an in-memory `ConcurrentHashMap`. `ArchicaMcpClient` wires them on the client with `ClientLitematicaBridge`. See [proposal.md](proposal.md) for motivation.

Because upcoming server-side shared blueprints (Model X) will reuse `src/main` persistence inside a Docker server volume, the persistence store must have zero client-only (`Minecraft`, `Litematica`, `MaLiLib`) dependencies.

## Goals / Non-Goals

**Goals:**
- Persist every blueprint draft's lossless AST (`schemaVersion`, `blueprintId`, `origin`, `facing`, `nextOpNumber`, `ops`) plus placement metadata (`activated`, `lastWorldOrigin`, `lastCompiledAt`) atomically to disk.
- Keep `BlueprintDraftStore` pure Kotlin/JVM (`java.nio.file.Path` + `kotlinx.serialization.json`) in `src/main` so both client and future server entrypoints share one implementation.
- Reload persisted drafts safely on startup before starting the embedded MCP server, isolating per-file errors.

**Non-Goals:**
- Importing external `.litematic` binary files into editable draft operations (deferred to a separate schematic-import change).
- Server networking, `ServerLevel` scanning, or multi-client hologram synchronization (deferred to `server-support`).
- Automatic deletion of `.draft.json` files when clearing Litematica placements.

## Decisions

### 1. Sidecar JSON location alongside `.litematic`
Store drafts at `<schematicsBaseDir>/archica/<blueprint_id>.draft.json` right beside `<blueprint_id>.litematic`.
- *Why*: Keeps a blueprint's editable history and compiled schematic portable together in one folder (`.minecraft/schematics/archica/` on client, `/data/schematics/archica/` on Docker server).
- *Alternative considered*: Writing into `config/archica-mcp/drafts/`. Rejected because schematic backups or Docker volume mounts focused on `schematics/` would miss the draft history.

### 2. Versioned serializable DTOs in `src/main`
Introduce `@Serializable` persistence models (`PersistedBlueprintDraft`, `PersistedBlueprintOp`, `PersistedPlacementState`, and explicit serializable primitive op variants using `type` matching `ArchicaMcpServerFactory.PRIMITIVE_TYPES`) with `schemaVersion = 1` and `ignoreUnknownKeys = true`.
- *Why*: Decouples on-disk JSON format from in-memory `MutableSession` internals and guarantees forward-compatible schema evolution without touching MCP tool argument parsers.
- *Alternative considered*: Reusing raw `JsonObject` maps from `BlueprintToolCodec`. Rejected because typed DTOs validate structure cleanly and catch malformed fields deterministically on reload.

### 3. Optional persistence hook on `BlueprintSessionStore` / `BlueprintService`
Inject an optional `BlueprintDraftStore? = null` into `BlueprintSessionStore` (or `BlueprintService` delegating persistence calls on `create`, `addOps`, `updateOp`, `removeOp`, `undo`, `compile`, and `clearPlacement`):
- Persist via atomic temp-file write + `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` with standard `REPLACE_EXISTING` fallback (matching `LitematicSchematicCompiler` and `ArchicaConfigStore`).
- Keep `draftStore = null` as the default so all existing pure-unit tests remain fast, in-memory, and side-effect free.
- When `create(overwrite = true)` runs, overwrite the on-disk `.draft.json` and reset `activated = false` placement metadata.
- When `clearPlacement(deleteFile = true)` runs, update `.draft.json` (`activated = false`) rather than deleting the draft file.

### 4. Client startup directory resolution and resilient reload
In `ArchicaMcpClient.onInitializeClient()`:
- Resolve `draftsDir` from `litematicaBridge.schematicsDirectory().resolve("archica")` with a fallback to `FabricLoader.getInstance().gameDir.resolve("schematics/archica")` if Litematica's directory lookup throws (for example, if optional Litematica is absent).
- Construct `BlueprintDraftStore(draftsDir)` and invoke `blueprintService.loadPersistedDrafts()` before launching the MCP server coroutine.
- During reload, each `*.draft.json` is validated (`blueprint_id` regex, filename stem match, `nextOpNumber >= 1`, unique monotonic `opId`s, and `BlockCatalog` / rasterizer validation of stored ops). Any file failing validation logs a `WARN` and is skipped so other drafts and game startup remain unaffected.

## Risks / Trade-offs

- **Write amplification on rapid op mutations** -> Blueprint sessions typically hold tens to a few hundred ops (<50 KB JSON). Atomic writes on `Dispatchers.IO` (or under per-session serialization lock) avoid noticeable latency while preventing partial JSON corruption on crashes.
- **Draft references a block from a temporarily unloaded mod** -> Per-file validation during reload logs a warning and skips that file instead of crashing startup; the `.draft.json` file remains untouched on disk so it can reload once the mod is present again.
- **In-memory `nextOpNumber` vs loaded `ops`** -> Ensure loaded `nextOpNumber` is clamped to at least `maxExistingNumericOpId + 1` so new ops never collide with existing `op_N` IDs even if a file was hand-edited.
