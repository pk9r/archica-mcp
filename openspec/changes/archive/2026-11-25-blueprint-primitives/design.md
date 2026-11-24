# Design

## Context

See [proposal.md](proposal.md) for motivation and scope, [specs/blueprint-primitives/spec.md](specs/blueprint-primitives/spec.md) for the blueprint engine behavioral contract, and [specs/mcp-server/spec.md](specs/mcp-server/spec.md) for the MCP tool additions.

Currently:
- [build.gradle.kts](../../build.gradle.kts) uses `loom { splitEnvironmentSourceSets() }`, keeping pure-Kotlin domain and MCP server code in `src/main/kotlin/` and Minecraft `26.3` adapters in `src/client/kotlin/`.
- [ArchicaMcpServer.kt](../../src/main/kotlin/com/archica/mcp/server/ArchicaMcpServer.kt) and [McpServerModels.kt](../../src/main/kotlin/com/archica/mcp/server/McpServerModels.kt) expose `get_build_context` and `scan_volume` with a unified `McpToolExecutor` JSON error envelope.
- [ClientThreadDispatcher.kt](../../src/client/kotlin/com/archica/mcp/context/ClientThreadDispatcher.kt) already provides bounded main-thread marshalling for reading player inventory (`WorldContextSource.getInventoryMaterials()`).

## Goals / Non-Goals

**Goals:**
- Keep all blueprint session state, coordinate transforms, blockstate parsing/auto-orientation, and primitive rasterization in pure Kotlin (`src/main/kotlin/com/archica/mcp/blueprint/`) so `./gradlew test` exercises every primitive and edge case without booting Minecraft.
- Keep `blueprint_create`, `blueprint_add_ops`, `blueprint_update_op`, `blueprint_remove_op`, `blueprint_undo`, `blueprint_inspect`, and `blueprint_list` non-blocking on background coroutine dispatchers, touching the Minecraft client main thread only for optional `survival_check` inventory lookups when a world is loaded.
- Provide deterministic, human/LLM-predictable rasterization semantics for every primitive, facing rotation, and auto-orientation rule.

**Non-Goals:**
- Writing `.litematic` NBT files, managing `schematics/archica/*.litematic`, or integrating with Litematica's `SchematicPlacementManager` (`blueprint_compile`, `blueprint_clear_placement`, and `LITEMATICA_UNAVAILABLE` belong to `litematica-compiler`).
- Persisting draft blueprint sessions across JVM restarts (sessions remain in-memory only).

## Decisions

### 1. `BlockCatalog` Port in `src/main` + `ClientBlockCatalog` in `src/client`
- **Decision:** Define a `BlockCatalog` interface in `src/main/kotlin/com/archica/mcp/blueprint/` with `resolve(blockId: String, properties: Map<String, String>): ResolvedBlockSpec` (throwing `BlueprintException(INVALID_BLOCK_ID, ...)` on unknown blocks or invalid property keys/values) and classification metadata (`BlockKind`: `SOLID`, `STAIRS`, `LOG_OR_PILLAR`, `DOOR`, `CONNECTABLE`, `AIR`). Implement `ClientBlockCatalog` in `src/client/kotlin/` using `BuiltInRegistries.BLOCK` and Minecraft's `BlockState` / `StateDefinition` properties, and provide `FakeBlockCatalog` in `src/test/kotlin/`.
- **Rationale:** `src/main` cannot depend on `net.minecraft.*` under Loom's `splitEnvironmentSourceSets()`. Validating against `BuiltInRegistries.BLOCK` works even on the Title Screen (since block registries initialize at mod/game bootstrap) and does not require main-thread world access.
- **Alternative considered:** Hardcoding a static list of Minecraft 26.3 block IDs in `src/main`, or dispatching every `add_ops` call to the Minecraft render thread. Rejected because a static list drifts across updates/mods, while main-thread dispatch is unnecessary for static registry lookup.

### 2. Thread-Safe Session Store & Atomic Batch Validation
- **Decision:** Store sessions in a `ConcurrentHashMap<String, BlueprintSession>` guarded per-session via a `Mutex` (or immutable session snapshot replacement under a lock) with a monotonically increasing `nextOpNumber` counter (`op_1`, `op_2`, ...). Validate and parse **all** operations in a `blueprint_add_ops` batch (including rasterization bounds / `BlockCatalog` validation) *before* committing any of them to the session.
- **Rationale:** Monotonic IDs prevent an AI agent from accidentally modifying a newly added operation that reused an old `op_id` after `remove_op`/`undo`. Atomic batch validation guarantees a failed `add_ops` call leaves the draft untouched.
- **Alternative considered:** Re-numbering `op_1..op_N` after every removal. Rejected because stable IDs are essential for `blueprint_update_op` and `blueprint_remove_op`.

### 3. Canonical Forward-Aligned Facing Transform & Blockstate Rotation
- **Decision:** Define local blueprint coordinates as forward-aligned (`+Y` up, `+Z` forward along blueprint `facing`, `+X` right when looking along `facing`) and provide a pure `BlueprintTransform` mapper to world space:
  - `NORTH` (`-Z` forward, `+X` right): `(wx, wy, wz) = (lx, ly, -lz)`; clockwise 90-degree turns = `0`
  - `EAST` (`+X` forward, `+Z` right): `(wx, wy, wz) = (lz, ly, lx)`; clockwise turns = `1`
  - `SOUTH` (`+Z` forward, `-X` right): `(wx, wy, wz) = (-lx, ly, lz)`; clockwise turns = `2`
  - `WEST` (`-X` forward, `-Z` right): `(wx, wy, wz) = (-lz, ly, -lx)`; clockwise turns = `3`
  - Rotate directional blockstate properties (`facing` across `north -> east -> south -> west`, `axis` swapping `x <-> z` on odd turns) by the same clockwise turn count.
- **Rationale:** Forward-aligned local space means an agent can always build a facade at `z = 0` or extend a room along `+Z` regardless of which cardinal direction the player is facing at `origin`. Keeping the transform pure lets `blueprint-primitives` expose world bounding boxes and lets `litematica-compiler` reuse the exact same transform when writing `.litematic` regions.
- **Alternative considered:** Treating `NORTH` as raw identity `(lx, ly, lz) -> (lx, ly, lz)` (`+Z` local = `+Z` world = South). Rejected because building "forward along `+Z`" when `facing = NORTH` would point backward relative to the player's view.

### 4. Deterministic Geometry & Bounds Cap for the 11 V1 Primitives
- **Decision:** Rasterize into a sparse/chunked or flat `VoxelGrid` keyed by local `(x, y, z)` with a hard cap of `MAX_RASTERIZED_VOLUME = 131_072` blocks on the enclosing bounding box (throwing `VOLUME_TOO_LARGE` if exceeded). Key geometry rules:
  - **`set_block`**: Places one voxel; if `BlockKind.DOOR` and `half` is omitted, places `half=lower` at `pos` and `half=upper` at `pos + (0, 1, 0)` (auto-orienting `facing` toward adjacent air if omitted, fallback `south`, `hinge=left`, `open=false`).
  - **`fill_box` / `hollow_box` / `clear_box` / `replace_box`**: Normalize `from..to` inclusively (`min..max`). `hollow_box` emits voxels on any boundary face (`x in {minX,maxX} || z in {minZ,maxZ}` and, when `walls_only=false`, `y in {minY,maxY}`). `replace_box` matches base `blockId` when `target` omits `[...]`, or exact specified properties when `[...]` is present.
  - **`line`**: Integer 3D Bresenham from `from` to `to`. When `BlockKind.LOG_OR_PILLAR` and `axis` is omitted, chooses `axis` from the largest delta (`|dx|`, `|dy|`, `|dz|`, tie-break `y > z > x`).
  - **`cylinder` / `dome`**: Use `(dx^2 + dz^2) <= (r + 0.5)^2` (and `dx^2 + dy^2 + dz^2 <= (r + 0.5)^2` with `dy >= 0` for `dome`) so small radii (`r = 2..6`) form well-proportioned Minecraft circles. When `hollow=true`, keep voxels where at least one 4-neighbor in XZ (`cylinder`) or 6-neighbor in 3D (`dome`) falls outside the solid condition.
  - **`gable_roof`**: Expand footprint by `overhang` (default `1`) on X and Z. Along the span perpendicular to `ridge_axis`, step inward from both eaves toward the center: odd span places `ridge_block` (defaulting to `<wood>_slab` or `block`) on the single center peak row; even span places two symmetric top steps (plus `ridge_block` row above only if explicitly requested or symmetric peak). Stairs auto-orient `facing` inward toward the ridge with `half=bottom`. Gable end walls are left open for explicit wall primitives.
  - **`staircase`**: Determine primary horizontal climb axis (`|dx|` vs `|dz|`, tie-break `z`). Step `t` from `0` to `steps = max(|dx|, |dy|, |dz|)`: interpolate `x, y, z` monotonically from `from` to `to`, expand `width` (default `1`, `>= 1`) along the orthogonal horizontal `+` axis, auto-orient `stairs` `facing` toward the ascent direction, and when `fill_under=true`, fill supporting voxels from `min(from.y, to.y)` up to `stepY - 1`.
  - **`repeat_pattern`**: Recursively evaluate `op` for `i in 0 until count` offsetting all coordinate fields by `i * step`. Enforce `count in 1..256` and nesting depth `<= 4` (`INVALID_ARGUMENTS` if exceeded).
- **Rationale:** Explicit mathematical rules prevent ambiguity between documentation and tests, avoid tiny-radius spiky circles, and protect the client JVM from memory spikes on malformed coordinates.

### 5. Optional Non-Blocking Survival Check via `SpatialContextService` / `WorldContextSource`
- **Decision:** Inject a suspend `InventorySnapshotProvider` (returning `GameModeAndInventory?`: `null` when no world is loaded, or `gameMode` + inventory counts via `ClientThreadDispatcher` when loaded) into `BlueprintService`. When `gameMode == "survival"`, compute `missing_materials` against required non-air block counts (mapping double-block doors to `1` item per doorway or counting placed base items consistently — `1` door item per placed `DOOR` operation/pair).
- **Rationale:** Reuses existing main-thread dispatch safely while ensuring creative-mode or disconnected blueprint drafting never fails with `NO_WORLD_LOADED`.

## Risks / Trade-offs

- **[Risk] Double-block doors counted as 2 blocks vs 1 survival door item** -> **Mitigation:** Aggregate material counts using base `blockId` where `DOOR` pairs (`half=lower` + `half=upper`) contribute `1` door item to `materials` / `survival_check` (or document clearly and test both `total_blocks` vs item count). Let's count `total_blocks` by rasterized non-air voxels (`2` voxels for a door) while counting `1` door item in `materials` when both halves come from `set_block`, or document `materials` as required survival block/item counts.
- **[Risk] `ClientBlockCatalog` property validation with Minecraft `26.3` `StateDefinition` API** -> **Mitigation:** Keep `ClientBlockCatalog` small in `src/client/kotlin/` using `BuiltInRegistries.BLOCK.getOptional(...)` + `stateDefinition.getProperty(name)` + `property.getValue(rawValue)` and verify compilation via `./gradlew compileClientKotlin`.
- **[Risk] Documentation drift between `docs/blueprint-primitives.md` and finalized rules** -> **Mitigation:** Include a task to sync `docs/blueprint-primitives.md` and `docs/mcp-tools.md` with the finalized facing mapping, volume cap, and implemented tool statuses.
