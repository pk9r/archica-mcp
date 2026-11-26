# Tasks

## 1. Upgrade Roadmap Documentation (`docs/roadmap.md`)

- [x] 1.1 Create `docs/roadmap.md` in English summarizing the `v0.1.0` implemented baseline (11 MCP tools, 11 primitives, in-memory `BlueprintSessionStore`, `.litematic` v7 single-region compiler, Litematica placement bridge, `/archica` client commands) and an ASCII dependency flow diagram covering Phases 1–4 (`v0.2.0` to `v0.5.0`), verifying all cited module/class names and tool names match the existing codebase and `docs/mcp-tools.md`
- [x] 1.2 Document each phase in `docs/roadmap.md` (`v0.2.0` Persistence & `.litematic` Import/World Capture, `v0.3.0` Primitives/Blockstates/ASCII Preview, `v0.4.0` Live Build Progress & Deep Litematica Bridge, `v0.5.0` Smart Indoor/Chest Context & Universal MCP Prompts/Resources), explicitly noting current gaps (such as `NbtBinaryCodec.readGzipCompound` / `LitematicaBitArray.getAt` unread read-paths and no TileEntity NBT support), value delivered, and open design questions for each phase; verify Markdown renders cleanly with plain ASCII diagrams and no broken relative links

## 2. README Roadmap Integration (`README.md`)

- [x] 2.1 Add `docs/roadmap.md` to the `Documentation` list in `README.md` and update `Status & Roadmap` to link `docs/roadmap.md` while listing the four planned post-`v0.1.0` phases as `Planned` beneath the completed `v0.1.0` items; verify all Markdown links in `README.md` resolve to existing files
