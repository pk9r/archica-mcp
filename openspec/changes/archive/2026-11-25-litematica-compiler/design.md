# Design

## Context

See [proposal.md](proposal.md) for motivation and scope, [specs/litematica-compiler/spec.md](specs/litematica-compiler/spec.md) for the compilation/placement contract, and [specs/mcp-server/spec.md](specs/mcp-server/spec.md) for MCP tool additions.

Current codebase boundaries:
- [build.gradle.kts](../../build.gradle.kts) uses `loom { splitEnvironmentSourceSets() }`, isolating pure-Kotlin logic in `src/main/kotlin/` from Minecraft `26.3` and Litematica `0.29.1` (`malilib` `0.30.2`) APIs in `src/client/kotlin/`.
- [BlueprintTransform.kt](../../src/main/kotlin/com/archica/mcp/blueprint/BlueprintTransform.kt) and [VoxelGrid.kt](../../src/main/kotlin/com/archica/mcp/blueprint/VoxelGrid.kt) already compute forward-aligned facing rotations and sparse local voxel buffers.
- [BlueprintSessionStore.kt](../../src/main/kotlin/com/archica/mcp/blueprint/BlueprintSessionStore.kt) exposes `BlueprintService` with an injected `activePlacementChecker: (String) -> Boolean` hook and `SurvivalSnapshotProvider`.
- [ClientThreadDispatcher.kt](../../src/client/kotlin/com/archica/mcp/context/ClientThreadDispatcher.kt) provides bounded main-thread marshalling (`runOnClientThread`) guarded by `NO_WORLD_LOADED`.

## Goals / Non-Goals

**Goals:**
- Keep `.litematic` voxel transformation, bit-packing, GZIP+NBT binary serialization, and atomic file writing in pure Kotlin (`src/main`) so `./gradlew test` validates binary compatibility without booting Minecraft.
- Run rasterization and `.litematic` disk serialization off the Minecraft render thread, marshalling only Litematica runtime state (`SchematicHolder`, `LitematicaSchematic`, `SchematicPlacementManager`) onto the client main thread.
- Keep all Litematica/MaLiLib calls behind a narrow `LitematicaBridge` port in `src/main` implemented by `ClientLitematicaBridge` in `src/client`.

**Non-Goals:**
- Exporting block entities (NBT inventories/signs) or entities in v1 (`TileEntities` and `Entities` remain empty lists).
- Supporting multi-subregion `.litematic` files or schematic rotation/mirror flags on `SchematicPlacement` (facing rotation is baked into the compiled region voxels and blockstates).
- Adding `/archica` client commands or MaLiLib GUI config screens.

## Decisions

### 1. Pure-Kotlin GZIP + NBT Encoder in `src/main` vs Minecraft `NbtIo` in `src/client`
- **Decision:** Implement a minimal, self-contained binary NBT writer (`TagCompound`, `TagList`, `TagLongArray`, `TagInt`, `TagLong`, `TagString`) over `java.util.zip.GZIPOutputStream` directly in `src/main`, injecting `minecraftDataVersion: Int` from `SharedConstants.getCurrentVersion().dataVersion().version()` on the client (with a valid `26.3` default for unit tests).
- **Rationale:** `src/main` cannot import `net.minecraft.nbt.*` under Loom's split source sets. A pure-Kotlin writer keeps compilation off the main thread and enables deterministic round-trip NBT decoding tests in JUnit 5 without Minecraft bootstrap overhead.
- **Alternative considered:** Passing `VoxelGrid` to `src/client` and encoding with `net.minecraft.nbt.NbtIo` + `LitematicaSchematic`. Rejected because it couples schematic encoding to client startup/registries and prevents fast unit testing of `.litematic` bit-packing.

### 2. `.litematic` v7 Bit-Packing & World-Normalized Origin (`worldBounds.min`)
- **Decision:** Transform every non-air voxel `(lx, ly, lz) -> (wxOffset, wyOffset, wzOffset)` and `BlockSpec` via `BlueprintTransform`, compute `worldBounds` (shifted by `origin_override` relative to `session.origin` when supplied), and normalize all schematic indices relative to `worldBounds.min` (`[0..sizeX-1, 0..sizeY-1, 0..sizeZ-1]` with subregion `Position = [0, 0, 0]`). Pack indices in `y * (sizeX * sizeZ) + z * sizeX + x` order into `LitematicaBitArray` layout (`bits = max(2, ceil(log2(paletteSize)))`, allowing entries to straddle 64-bit `long` boundaries). Place the resulting `SchematicPlacement` at `worldBounds.min` (returned as `world_origin`).
- **Rationale:** Primitives such as `gable_roof(overhang = 1)` produce negative local coordinates (`-1`), and `EAST`/`SOUTH`/`WEST` rotations shift the minimal corner relative to local `(0, 0, 0)`. Normalizing to `[0..size-1]` with `Position = [0, 0, 0]` ensures generated `.litematic` files open cleanly without negative offsets even when loaded manually by the player, while placing at `worldBounds.min` aligns the hologram identically to `world_bounding_box` from `blueprint_inspect`.
- **Alternative considered:** Placing `SchematicPlacement` at `blueprint.origin` and writing a non-zero negative `Position` inside the subregion tag. Rejected because manual Litematica usage expects subregion origin `[0, 0, 0]` and positive `Size`.

### 3. `LitematicaBridge` Port, `SchematicHolder` Cache Eviction & Placement Tagging
- **Decision:** Define a `LitematicaBridge` interface in `src/main` with `schematicsDirectory(): Path`, `activatePlacement(blueprintId, schematicPath, worldOrigin): Boolean`, `clearPlacement(blueprintId): Boolean`, and `hasActivePlacement(blueprintId): Boolean`. Implement `ClientLitematicaBridge` in `src/client` using:
  - `DataManager.getSchematicsBaseDirectory().resolve("archica")` for the base directory.
  - Placement naming/tagging convention `"archica:<blueprint_id>"`, tracking and scanning `DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()` by name so replacement/clearance works reliably even across reloads.
  - Cache eviction before reload: remove any existing `LitematicaSchematic` in `SchematicHolder.getInstance().getAllSchematics()` matching the target `Path` before loading freshly via `LitematicaSchematic.createFromFile(dir, fileName)` (since `SchematicHolder.getOrLoad` would otherwise return a stale in-memory schematic after overwriting the file).
  - Removal then addition: remove existing placement(s) named `"archica:<blueprint_id>"` via `SchematicPlacementManager.removeSchematicPlacement`, create `SchematicPlacement.createFor(schematic, BlockPos, "archica:<blueprint_id>", true, true)`, and register via `addSchematicPlacement(placement, false)`.
- **Rationale:** Verified directly against `litematica-ZqV316KY.jar` (`0.29.1` for MC `26.3`): `SchematicHolder.getOrLoad` caches by `Path`, and `LitematicaSchematic.createFromFile(dir, fileName)` automatically resolves `.litematic`. Wrapping all Litematica calls in `try/catch` and mapping failures to `LITEMATICA_UNAVAILABLE` isolates runtime mod issues while preserving the written `.litematic` file.
- **Alternative considered:** Calling `SchematicHolder.getInstance().getOrLoad(path)` directly. Rejected because iterative `blueprint_compile` calls overwrite the same path and would render stale holograms.

### 4. World-Loaded Semantics for `activate=false` vs `activate=true` & Clear
- **Decision:** Reject empty blueprints (`totalBlocks == 0`) with `INVALID_ARGUMENTS` before disk I/O. Perform file compilation and atomic temp-file-to-target move in `Dispatchers.IO` without requiring a loaded world, so `blueprint_compile(activate = false)` succeeds on the title screen. Only dispatch to `ClientThreadDispatcher` when `activate = true` or during `blueprint_clear_placement` (failing with `NO_WORLD_LOADED` if no world is loaded, or `LITEMATICA_UNAVAILABLE` if Litematica throws, citing `schematic_path` if already written).
- **Rationale:** Matches `blueprint-primitives` offline drafting philosophy while keeping Litematica world/renderer mutations strictly on the main thread when a world is active.

## Risks / Trade-offs

- **[Risk] `LitematicaBitArray` bit-straddling off-by-one bug** -> **Mitigation:** Mirror the exact bitwise packing/unpacking of Litematica's `LitematicaBitArray.setAt`/`getAt` and write a JUnit test decoding compiled `.litematic` bytes with a minimal NBT reader across bit widths `2`, `3`, `4`, and `5`.
- **[Risk] `DataManager.getSchematicsBaseDirectory()` accessed off the main thread** -> **Mitigation:** Resolve the base directory via `LitematicaBridge.schematicsDirectory()` (falling back safely or marshalling onto client thread / catching MaLiLib errors into `LITEMATICA_UNAVAILABLE`), and make `schematicsDirectory` injectable for pure-Kotlin tests using `@TempDir`.
- **[Risk] `hasActivePlacement` read from `blueprint_list` on Ktor coroutine thread** -> **Mitigation:** Track active placement IDs in a thread-safe `ConcurrentHashMap.newKeySet<String>()` updated on activate/clear/world-unload (or query `CopyOnWriteArrayList` safely), avoiding blocking `blueprint_list` on main-thread dispatch.
