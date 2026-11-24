# Proposal

## Why

`archica-mcp` is currently a greenfield repository containing only OpenSpec scaffolding. Before implementing the Fabric client mod, we need concise, authoritative English documentation that captures the agreed system design, MCP contracts, spatial compression strategy, and Litematica integration workflow so contributors and AI agents share a single source of truth.

## What Changes

- Add `README.md` at the repository root introducing `archica-mcp`, its target stack (Minecraft 26.3, Fabric, Fabric Language Kotlin, MaLiLib, Litematica, MCP Kotlin SDK), local MCP client connection guide (IDE, Claude, Antigravity), and project roadmap.
- Add `docs/architecture.md` documenting the embedded localhost Streamable HTTP MCP server, Ktor-to-Minecraft client thread marshalling, `.litematic` NBT compilation, and Litematica placement lifecycle (create, replace, clear).
- Add `docs/spatial-context.md` defining the player-radius world scanner, concise 2D heightmap + surface legend encoding, inventory/hotbar material summary, and optional RLE-compressed 3D volume inspection.
- Add `docs/blueprint-primitives.md` specifying stateful blueprint sessions in memory, anchor-relative coordinate transforms, blockstate auto-orientation rules, and the v1 building primitives catalog.
- Add `docs/mcp-tools.md` specifying the planned MCP tool schemas, request/response payloads, and error handling when no world is loaded or survival materials are missing.

## Capabilities

### New Capabilities

<!-- None: pure documentation change (`skip_specs: true` in `.openspec.yaml`). -->

### Modified Capabilities

<!-- None: no existing capabilities or runtime requirements are modified. -->

## Impact

- New documentation files: `README.md`, `docs/architecture.md`, `docs/spatial-context.md`, `docs/blueprint-primitives.md`, and `docs/mcp-tools.md`.
- No source code, Gradle build scripts, or runtime mod artifacts are created or modified by this change.
