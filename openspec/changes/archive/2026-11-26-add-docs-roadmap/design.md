# Design

## Context

See `proposal.md` for motivation and scope. Existing repository documentation (`README.md`, `docs/architecture.md`, `docs/spatial-context.md`, `docs/blueprint-primitives.md`, `docs/mcp-tools.md`, `docs/antigravity-setup.md`) is written in English, uses plain ASCII architecture diagrams, and treats `v0.1.0` as the implemented baseline. `README.md` currently has a `Status & Roadmap` section listing only completed v1 milestones (`1..5`).

## Goals / Non-Goals

**Goals:**
- Capture the four post-`v0.1.0` upgrade phases (`v0.2.0` through `v0.5.0`) in a dedicated `docs/roadmap.md` document so future OpenSpec changes can reference a cohesive, dependency-ordered plan.
- Ground each phase in the current codebase architecture (`BlueprintSessionStore`, `NbtBinaryCodec`, `LitematicaBitArray`, `ClientLitematicaBridge`, `BuildContextScanner`, `ArchicaMcpServer`) while keeping technical options and open design questions explicit.
- Update `README.md` (`Documentation` and `Status & Roadmap`) so readers can discover the post-v0.1.0 roadmap without cluttering the main README.

**Non-Goals:**
- Locking exact MCP tool signatures, JSON schemas, or NBT/sidecar file formats before each phase enters its own OpenSpec change.
- Modifying any existing OpenSpec capability spec (`openspec/specs/*`) or mod source code.

## Decisions

- **Dedicated `docs/roadmap.md` + concise `README.md` summary** (over putting the entire multi-phase roadmap inline in `README.md`): Keeps `README.md` scannable as a setup and capability overview while giving `docs/roadmap.md` room for an ASCII phase dependency diagram, phase-by-phase rationale, and open design questions.
- **English documentation with plain ASCII diagrams** (over Vietnamese or Unicode box-drawing): Matches the rest of `docs/*.md` and `README.md`, ensuring consistent terminal/Markdown rendering across environments.
- **Status-marked phases (`Planned` / `Exploring`) separated from `v0.1.0` (`Done`)**: Prevents AI agents and users from mistaking planned v0.2+ features for implemented `v0.1.0` capabilities.

## Risks / Trade-offs

- **Roadmap drift from future implementation** -> Mitigation: State explicitly in `docs/roadmap.md` that phases are directional and require dedicated OpenSpec proposals/specs before implementation, and avoid specifying unimplemented tool names as finalized contracts.
