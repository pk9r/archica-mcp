# Proposal

## Why

With `world-context-compression` and the embedded `mcp-server` already active, AI agents can inspect Minecraft terrain and call tools over Streamable HTTP, but they still have no way to draft or iteratively refine structures. Implementing stateful in-memory blueprint sessions and composable building primitives first creates a fast, pure-Kotlin drafting and rasterization core that can be thoroughly tested via MCP before `.litematic` NBT writing and Litematica hologram placement are added in `litematica-compiler`.

## What Changes

- Introduce the `blueprint-primitives` capability for stateful, in-memory architectural drafting and voxel rasterization:
  - **In-memory blueprint session store:** Manage named draft sessions (`blueprint_id`, world `origin`, cardinal `facing`, and ordered `ops`) in a thread-safe store with monotonically increasing `op_id`s (`op_1`, `op_2`, ...) that are never reused after `remove_op` or `undo`.
  - **Local anchor-relative coordinate system & facing rotation:** Evaluate all primitive coordinates relative to `(0, 0, 0)` with inclusive `[min, max]` box normalization, and provide canonical forward-aligned transforms from blueprint local space (`+Z` forward, `+X` right, `+Y` up) to world/schematic coordinates and directional blockstate properties (`facing`, `axis`) for `NORTH`, `SOUTH`, `EAST`, and `WEST`.
  - **Block validation & auto-orientation (`BlockCatalog` port):** Validate block identifiers and bracketed blockstate properties against a pure-Kotlin `BlockCatalog` abstraction backed by Minecraft's `BuiltInRegistries.BLOCK` on the client (`INVALID_BLOCK_ID`), and auto-orient `stairs`, `logs`/pillars, and two-block `doors` (`half=lower`/`upper`) when explicit properties are omitted.
  - **11 composable v1 building primitives:** Sequentially rasterize `set_block`, `fill_box`, `hollow_box`, `line`, `cylinder`, `dome`, `gable_roof`, `staircase`, `replace_box`, `clear_box`, and nested `repeat_pattern` (bounded depth and count) with a hard rasterized voxel safety cap to prevent runaway allocations.
  - **Inspection & non-blocking survival material check:** Compute enclosing bounding boxes, block counts, material palettes, and optional survival `missing_materials` warnings against player inventory without requiring disk I/O or blocking creative/disconnected drafting.
- Extend `mcp-server` to expose the 7 blueprint draft session tools (`blueprint_create`, `blueprint_add_ops`, `blueprint_update_op`, `blueprint_remove_op`, `blueprint_undo`, `blueprint_inspect`, `blueprint_list`) and map blueprint domain errors (`BLUEPRINT_EXISTS`, `BLUEPRINT_NOT_FOUND`, `OP_NOT_FOUND`, `INVALID_BLOCK_ID`, `VOLUME_TOO_LARGE`) into the unified JSON error envelope.
- Explicitly defer `.litematic` NBT compilation, disk persistence, `blueprint_compile`, `blueprint_clear_placement`, and `LitematicaBridge` (`LITEMATICA_UNAVAILABLE`) to the follow-up `litematica-compiler` change.

## Capabilities

### New Capabilities

- `blueprint-primitives`: Stateful in-memory blueprint sessions, anchor-relative coordinate and facing transforms, `BlockCatalog` blockstate validation and auto-orientation, sequential rasterization of the 11 v1 building primitives, and non-blocking survival material summaries.

### Modified Capabilities

- `mcp-server`: Expose the 7 blueprint draft session MCP tools (`blueprint_create`, `blueprint_add_ops`, `blueprint_update_op`, `blueprint_remove_op`, `blueprint_undo`, `blueprint_inspect`, `blueprint_list`) and extend unified structured error code mappings for blueprint validation and session errors.

## Impact

- **Pure Kotlin blueprint core (`src/main/kotlin/com/archica/mcp/blueprint/`):** Session store, primitive operation models, coordinate/facing transformer, blockstate parser & auto-orienter, 11 primitive rasterizers, voxel buffer, `BlockCatalog` port, and `BlueprintService`.
- **Pure Kotlin MCP server (`src/main/kotlin/com/archica/mcp/server/`):** JSON schemas, argument parsers, response formatters, and tool registrations for the 7 blueprint tools plus `McpErrorCode` additions.
- **Fabric client adapters (`src/client/kotlin/com/archica/mcp/`):** `ClientBlockCatalog` backed by `BuiltInRegistries.BLOCK`, optional main-thread survival inventory lookup via `ClientThreadDispatcher`, and `ArchicaMcpClient` wiring.
- **Unit & integration tests (`src/test/kotlin/com/archica/mcp/blueprint/`, `src/test/kotlin/com/archica/mcp/server/`):** JUnit 5 tests covering every primitive, facing rotation, auto-orientation, monotonic `op_id` lifecycle, `FakeBlockCatalog` validation, and end-to-end MCP tool calls over Streamable HTTP.
- **Documentation (`docs/blueprint-primitives.md`, `docs/mcp-tools.md`):** Align primitive geometry details, facing mapping, and implemented tool statuses.
