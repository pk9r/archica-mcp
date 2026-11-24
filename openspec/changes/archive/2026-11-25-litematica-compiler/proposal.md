# Proposal

## Why

With `world-context-compression`, the embedded `mcp-server`, and `blueprint-primitives` already implemented, AI agents can inspect Minecraft terrain and iteratively draft structures in memory, but those blueprints still cannot be exported to `.litematic` files or rendered as live Litematica holograms in the client. Completing the `.litematic` compiler and Litematica placement bridge closes the core Archica MCP co-building loop so drafts can be compiled, previewed, updated in place without duplicate holograms, and cleared directly from Minecraft `26.3`.

## What Changes

- Introduce the `litematica-compiler` capability for compiling blueprint draft sessions into standard `.litematic` files and managing live Litematica hologram placements:
  - **World-oriented `.litematic` NBT compiler (`src/main`):** Rotate rasterized local voxels and directional blockstate properties (`facing`, `axis`) by the blueprint's cardinal `facing`, normalize enclosing coordinates to `[0..size-1]` (`Position = [0, 0, 0]`), pack blockstates into Litematica v7 (`SubVersion = 1`) bit-packed `LongArray` format (`y * sizeX * sizeZ + z * sizeX + x`, `bits >= 2` straddling 64-bit longs), encode `Metadata` (`Name`, `Author`, `Description`, `RegionCount`, `TotalVolume`, `TotalBlocks`, `TimeCreated`, `TimeModified`, `EnclosingSize`, `MinecraftDataVersion`), and atomically write GZIP-compressed `.litematic` NBT files under `<schematicsBaseDir>/archica/<blueprint_id>.litematic`.
  - **`LitematicaBridge` placement lifecycle (`src/client`):** Marshal Litematica operations onto the Minecraft client main thread, resolve Litematica's configured base schematics directory (`DataManager.getSchematicsBaseDirectory()`), evict stale `SchematicHolder` cache entries for overwritten files, load compiled schematics via `LitematicaSchematic.createFromFile`, replace any existing `"archica:<blueprint_id>"` placement atomically via `SchematicPlacementManager`, support clearing active placements (with optional file deletion), and report live placement presence to `BlueprintService` (`blueprint_list`).
  - **Title-screen & empty-blueprint safety:** Reject empty blueprints (`totalBlocks == 0`) with `INVALID_ARGUMENTS` before writing; allow `activate = false` disk compilation even when no world is loaded; require a loaded world (`NO_WORLD_LOADED`) only when activating or clearing live placements; and report `LITEMATICA_UNAVAILABLE` (including the written `schematic_path` when applicable) if the Litematica runtime adapter fails.
- Extend `mcp-server` to expose the final 2 v1 Litematica MCP tools (`blueprint_compile`, `blueprint_clear_placement`) and map `LITEMATICA_UNAVAILABLE` in `McpErrorCode`.

## Capabilities

### New Capabilities

- `litematica-compiler`: Pure-Kotlin `.litematic` v7 GZIP/NBT compilation, world-facing voxel normalization, atomic schematic file output, and client-side `LitematicaBridge` placement activation, replacement, and clearance.

### Modified Capabilities

- `mcp-server`: Register `blueprint_compile` and `blueprint_clear_placement` MCP tools, wire live active-placement status into `blueprint_list`, and extend the unified JSON error envelope with `LITEMATICA_UNAVAILABLE`.

## Impact

- **Pure Kotlin compiler (`src/main/kotlin/com/archica/mcp/litematica/` or `blueprint/`):** Binary GZIP + NBT encoder, `LitematicaBitArray` packer, `LitematicSchematicCompiler`, `BlueprintCompilerService` / `LitematicaBridge` port, and `BlueprintService` integration (`activePlacementChecker` + `compile`/`clearPlacement`).
- **Pure Kotlin MCP server (`src/main/kotlin/com/archica/mcp/server/`):** Schemas, argument parsing, response formatting, and tool registration for `blueprint_compile` and `blueprint_clear_placement`, plus `McpErrorCode.LITEMATICA_UNAVAILABLE`.
- **Fabric client adapters (`src/client/kotlin/com/archica/mcp/`):** `ClientLitematicaBridge` wrapping `DataManager`, `SchematicHolder`, `LitematicaSchematic`, `SchematicPlacement`, and `SchematicPlacementManager` via `ClientThreadDispatcher`, `SharedConstants` data version provider, and `ArchicaMcpClient` wiring.
- **Unit & integration tests (`src/test/kotlin/com/archica/mcp/`):** JUnit 5 tests decoding compiled `.litematic` GZIP/NBT bytes (palette, `LitematicaBitArray` bit-straddling, `Y->Z->X` indexing, facing rotation, negative local offsets, metadata), fake `LitematicaBridge` placement replacement/clearing/error paths, and end-to-end MCP tool calls over Streamable HTTP.
- **Documentation (`docs/architecture.md`, `docs/blueprint-primitives.md`, `docs/mcp-tools.md`):** Update v1 implementation status and finalized `world_origin` (`worldBounds.min`) / schema details.
