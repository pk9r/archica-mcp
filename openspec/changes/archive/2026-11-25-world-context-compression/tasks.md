# Tasks

## 1. Pure Context Models & `get_build_context` Compression Core

- [x] 1.1 Define pure context models, `WorldContextSource` interface, and structured error types (`NO_WORLD_LOADED`, `VOLUME_TOO_LARGE`, `INVALID_RADIUS`, `INVALID_BOUNDS`) under `src/main/kotlin/com/archica/mcp/context/`, verified by `./gradlew compileKotlin`
- [x] 1.2 Implement outdoor top-down column scanning over `[playerY - 16, playerY + 24]` with pass-through clutter filtering (preserving canopies, logs, liquids, and roofs) plus unit tests in `src/test/kotlin/com/archica/mcp/context/` verified via `./gradlew test`
- [x] 1.3 Implement deterministic 1-to-1 `[SURFACE_LEGEND]` symbol allocation (excluding digits, `+`, `-`, `?`, whitespace) and aligned non-RLE `[TERRAIN_GRID]` formatting (`<symbol><deltaY>` and out-of-window marker) with unit tests verified via `./gradlew test`
- [x] 1.4 Implement outdoor `suggested_origin` solver (~10 blocks ahead along yaw with Chebyshev `<= 3` solid non-liquid/non-leaf fallback) and `[INVENTORY_MATERIALS]` descending count aggregator with unit tests verified via `./gradlew test`

## 2. `scan_volume` 3D RLE Encoder & Blockstate Normalizer

- [x] 2.1 Implement bounded 3D volume validation (`min <= max`, hard cap `13,824` blocks -> `VOLUME_TOO_LARGE`), `0=minecraft:air` palette, non-empty Y-slice omission, and X-axis RLE (`count*paletteId`) with unit tests in `src/test/kotlin/com/archica/mcp/context/` verified via `./gradlew test`
- [x] 2.2 Implement `include_states` (default `false`) compact blockstate property filtering (retaining geometric/non-default properties and stripping defaults/noise like `waterlogged=false`, `distance`, `persistent`) with unit tests verified via `./gradlew test`
- [x] 2.3 Update `docs/spatial-context.md` and `docs/mcp-tools.md` to match the finalized v1 contracts (`radius` default `12` / max `24`, aligned non-RLE grid, removal of `[OBSTACLES]` / `include_obstacles`, and `scan_volume.include_states`) and verify payload examples align with the unit test outputs

## 3. Minecraft Client-Thread Adapter & Build Verification

- [x] 3.1 Implement `ClientWorldContextSource` in `src/client/kotlin/com/archica/mcp/context/` reading `ClientWorld` blockstates, biome, dimension, world height limits, and `ClientPlayerEntity` pose, game mode, and hotbar/main inventory with `NO_WORLD_LOADED` guard, verified by `./gradlew compileClientKotlin`
- [x] 3.2 Implement bounded main-thread `MinecraftClient.execute` + `CompletableDeferred` dispatcher helper for safely running context scans from off-thread callers and verify full project compilation and tests with `./gradlew build`
