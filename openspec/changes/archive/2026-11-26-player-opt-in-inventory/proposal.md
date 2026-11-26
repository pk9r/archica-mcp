# Proposal

## Why

Currently, Archica MCP unconditionally reads the player's inventory in `get_build_context` (emitting `[INVENTORY_MATERIALS]`) and runs `survival_check` against carried items on every blueprint mutation, inspection, and compilation when in Survival mode. In practice, this biases AI architects toward whatever random blocks happen to be in the player's pockets rather than designing freely with materials stored in chests or planned for later gathering, adds unnecessary token noise, and forces pure-CPU blueprint draft operations to hop onto the Minecraft client main thread. Making player inventory reading and survival material checks **off by default** and **explicitly opt-in by the player** (resetting to off on each game launch) gives players control over when inventory constraints apply while keeping free-form creative/survival designing fast and unbiased.

## What Changes

- **Default-off inventory tracking**: Disable player inventory scanning (`[INVENTORY_MATERIALS]`) and survival material comparison (`survival_check`) by default when the Minecraft client starts, resetting to off on every game launch (in-memory only, no disk config persistence).
- **Player opt-in client commands**: Add `/archica inventory on`, `/archica inventory off`, and `/archica inventory` client-side commands to let the player actively enable, disable, or inspect inventory tracking during a session, and include inventory tracking status in `/archica status`.
- **Context output omission when off**: Update `get_build_context` so that when inventory tracking is disabled (the default), it skips reading `player.inventory` and omits the `[INVENTORY_MATERIALS]` section entirely (avoiding misleading `none` output). When enabled by the player, existing aggregation and sorting behavior applies unchanged.
- **Blueprint survival check gating**: Update `BlueprintService` / `SurvivalSnapshotProvider` wiring so that when inventory tracking is disabled, `survival_check` is skipped without dispatching to the Minecraft client main thread, leaving blueprint drafting pure-CPU and omitting `survival_check` from JSON payloads.
- **MCP server metadata alignment**: Update MCP `SERVER_INSTRUCTIONS` and `get_build_context` tool description so the AI does not expect `[INVENTORY_MATERIALS]` or `survival_check` unless the player has enabled inventory tracking.

## Capabilities

### New Capabilities
<!-- None -->

### Modified Capabilities
- `world-context-compression`: Make `[INVENTORY_MATERIALS]` opt-in via player toggle (off by default); omit `[INVENTORY_MATERIALS]` and skip reading player inventory when disabled.
- `blueprint-primitives`: Gate `survival_check` computation on the player's opt-in inventory toggle so draft mutations, inspections, and compilations omit `survival_check` by default.
- `mcp-server`: Update `get_build_context` payload/description and `SERVER_INSTRUCTIONS` to reflect default-off, player-controlled inventory reporting.
- `client-ux`: Add `/archica inventory [on|off]` client command and display current inventory tracking state in `/archica status` (defaulting to off on each client startup).

## Impact

- **Core / Context**: `BuildContextRequest`, `BuildContextResult`, `BuildContextScanner` (support optional/opt-in inventory inclusion and omitting `[INVENTORY_MATERIALS]`).
- **Client Integration**: `ArchicaMcpClient`, `ClientThreadDispatcher`, `ArchicaClientCommands` (in-memory `AtomicBoolean` toggle defaulting to `false`, `/archica inventory` commands, `/archica status` output).
- **MCP Server**: `ArchicaMcpServer` (`SERVER_INSTRUCTIONS` and `get_build_context` description).
- **Tests & Docs**: Unit/integration tests for `BuildContextScanner`, `BlueprintService`, `ArchicaMcpServer`, plus `docs/spatial-context.md`, `docs/mcp-tools.md`, `docs/architecture.md`, and `README.md`.
