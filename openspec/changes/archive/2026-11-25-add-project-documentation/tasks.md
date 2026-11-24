# Tasks

## 1. Root Project Overview

- [x] 1.1 Author `README.md` in English covering project overview, problem statement, core workflow diagram, target dependency baseline (Minecraft `26.3`, Fabric API `0.167.0+26.3`, Fabric Language Kotlin `1.14.2+kotlin.2.4.21`, Litematica `0.29.1`, MaLiLib `0.30.2`, MCP Kotlin SDK `0.16.0`), local Streamable HTTP MCP client setup (`http://127.0.0.1:25580/mcp` for Antigravity, Claude, and IDE clients), and implementation roadmap; verify all links to `docs/*.md` match planned filenames and clearly distinguish planned behavior from implemented code.

## 2. Architecture & Litematica Integration Docs

- [x] 2.1 Author `docs/architecture.md` in English documenting the embedded Ktor CIO Streamable HTTP MCP server, loopback DNS-rebinding protection, coroutine-to-Minecraft client thread marshalling (`MinecraftClient.getInstance().execute`), `.litematic` NBT file generation in `.minecraft/schematics/`, and Litematica placement lifecycle (`LitematicaSchematic.createFromFile`, `SchematicPlacementManager`, and replacement on re-compile); verify all ASCII component/sequence diagrams render cleanly and cover missing-world error handling.

## 3. Spatial Context & Blueprint Primitives Docs

- [x] 3.1 Author `docs/spatial-context.md` in English defining the player-radius scanner (`radius = 16` default `33x33` XZ grid), player pose and suggested anchor calculation, inventory/hotbar material summary format, compact 2D relative heightmap + surface block legend encoding, and on-demand `scan_volume` RLE Y-slice format; verify an end-to-end sample payload is included and stays concise.
- [x] 3.2 Author `docs/blueprint-primitives.md` in English documenting the stateful in-memory blueprint session lifecycle (`create`, `add_ops`, `update_op`, `remove_op`, `undo`, `compile`), anchor-relative `(0, 0, 0)` coordinates and facing transforms, blockstate auto-orientation rules (`stairs`, `logs`, `doors`, `glass_pane`), and the v1 primitives catalog (`set_block`, `fill_box`, `hollow_box`, `line`, `cylinder`, `dome`, `gable_roof`, `staircase`, `replace_box`, `clear_box`, `repeat_pattern`); verify every primitive lists its parameters and expected voxel behavior.

## 4. MCP Tool Contracts & Documentation Cross-Check

- [x] 4.1 Author `docs/mcp-tools.md` in English specifying the v1 MCP tool surface (`get_build_context`, `scan_volume`, `blueprint_create`, `blueprint_add_ops`, `blueprint_update_op`, `blueprint_remove_op`, `blueprint_undo`, `blueprint_inspect`, `blueprint_list`, `blueprint_compile`, `blueprint_clear_placement`), JSON input/output schemas, survival material-check warnings, and standard error payloads; verify tool names and schemas are consistent with `docs/spatial-context.md` and `docs/blueprint-primitives.md`.
- [x] 4.2 Review all five Markdown files (`README.md` and `docs/*.md`) for English consistency, valid relative cross-links across files, and alignment with the baseline versions and decisions recorded in `design.md`; verify no broken relative links or conflicting tool/primitive names remain.
