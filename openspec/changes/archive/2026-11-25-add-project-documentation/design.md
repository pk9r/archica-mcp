# Design

## Context

See `proposal.md` for motivation and scope. Currently the repository contains only `openspec/`, with no source files or documentation. All design decisions explored prior to implementation (target release Minecraft 26.3, Fabric + Fabric Language Kotlin, embedded localhost Streamable HTTP MCP server, player-radius spatial context compression, stateful in-memory blueprint sessions, and `.litematic` compilation + automatic Litematica placement) need to be organized into readable, cross-linked English Markdown documents.

## Goals / Non-Goals

**Goals:**
- Structure the project documentation into a modular, maintainable set of Markdown files (`README.md` + `docs/`) with clear boundaries between user setup, system architecture, spatial context format, blueprint primitives, and MCP tool contracts.
- Keep documentation concise, scannable, and concrete (ASCII architecture/flow diagrams, example JSON payloads, and compact terrain output examples) so both human developers and AI agents can consume it directly.
- Explicitly mark implemented vs. planned status since no mod code exists yet.

**Non-Goals:**
- Authoring Gradle build files, Kotlin source code, or automated doc-generation tooling.
- Defining formal OpenSpec behavioral delta specs (`specs/` is skipped via `skip_specs: true` until implementation changes begin).
- Documenting legacy Minecraft versions (`1.20.x`, `1.21.x`) or stdio-first external MCP bridge architectures beyond brief notes.

## Decisions

### 1. Multi-file Documentation Layout (`README.md` + 4 `docs/` guides)
- **Decision**: Split documentation into a concise `README.md` entry point plus four focused documents (`docs/architecture.md`, `docs/spatial-context.md`, `docs/blueprint-primitives.md`, `docs/mcp-tools.md`) rather than a single monolithic `README.md`.
- **Rationale**: Keeps the root README approachable for players/users setting up the mod with Claude, Cursor, or Antigravity, while allowing deep, unambiguous technical specifications for each subsystem (transport/threading, compression, primitives, MCP schemas).
- **Alternative Considered**: Putting everything in `README.md` or only `docs/DESIGN.md`. Rejected because a single long file mixes user setup with dense protocol/schema details and becomes hard to reference during future OpenSpec implementation changes.

### 2. Documented Baseline Architecture & Version Targets
- **Decision**: Document the target runtime baseline as Minecraft `26.3` (latest stable release), Fabric API (`0.167.0+26.3`), Fabric Language Kotlin (`1.14.2+kotlin.2.4.21`), Litematica (`0.29.1`), MaLiLib (`0.30.2`), and official `io.modelcontextprotocol:kotlin-sdk` (`0.16.0`) over Ktor CIO Streamable HTTP (`http://127.0.0.1:25580/mcp` by default).
- **Rationale**: Recording concrete baseline versions and transport choices prevents drift across future implementation changes and clarifies why loopback DNS-rebinding protection and client-thread marshalling are first-class requirements.

### 3. Documenting Player-Radius-First Context + Stateful Blueprints + File-Backed Litematica Activation
- **Decision**: Document:
  - **Context**: Player-radius scanning (`radius = 16` default, `33x33` XZ surface heightmap + legend + inventory/hotbar summary) as the primary context source, with `scan_volume` RLE Y-slices as on-demand drill-down.
  - **Primitives**: Stateful in-memory blueprint sessions with anchor-relative coordinates `(0,0,0)` and a focused v1 primitive set (`set_block`, `fill_box`, `hollow_box`, `line`, `cylinder`, `dome`, `gable_roof`, `staircase`, `replace_box`, `clear_box`, `repeat_pattern`).
  - **Compilation & Activation**: Writing standard `.litematic` NBT files to `.minecraft/schematics/` and triggering `LitematicaSchematic.createFromFile` + `SchematicPlacementManager` (replacing prior placements for the same `blueprint_id` so iterative edits do not stack overlapping holograms).
- **Rationale**: Capturing these contracts in English documentation upfront establishes a clean boundary for subsequent feature OpenSpec changes (`mcp-server`, `spatial-context`, `blueprint-compiler`, `litematica-bridge`).

## Risks / Trade-offs

- **Version Churn Risk**: Minecraft 26.x / Fabric / Litematica patch numbers evolve quickly -> Mitigation: Present specific dependency versions in `README.md` and `docs/architecture.md` as verified baseline targets and note where Gradle/Modrinth coordinates should be checked at implementation time.
- **Doc-to-Code Drift**: Writing detailed tool schemas and file formats before code exists can diverge from actual implementation details -> Mitigation: Keep schemas explicitly labeled as v1 design contracts and update them as part of each upcoming implementation OpenSpec change.
- **Litematica Internal API Coupling**: Documenting `DataManager` / `SchematicPlacementManager` assumes Litematica's internal Java signatures remain consistent in `26.3` -> Mitigation: Flag the Litematica bridge behind an isolated adapter interface in `docs/architecture.md` so internal hook adjustments do not affect the compiler or MCP layer.
