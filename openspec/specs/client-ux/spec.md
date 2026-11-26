# client-ux Specification

## Purpose

Defines the `client-ux` capability for in-game `/archica` client commands and lightweight player chat notifications for the embedded MCP server and Litematica hologram lifecycle.

## Requirements

### Requirement: Client status command
The system SHALL register `/archica status` as a client-side command displaying whether the embedded MCP server is running or stopped, its loopback endpoint URL, whether player inventory tracking is enabled or disabled (`off` by default), the number of in-memory blueprint draft sessions, and currently active `archica:` Litematica placements.

#### Scenario: Running server status displayed in client chat
- **WHEN** the player runs `/archica status` while the MCP server is listening on `127.0.0.1:25580` with `1` draft session, `1` active placement, and default inventory tracking disabled
- **THEN** the command prints the running state, `http://127.0.0.1:25580/mcp`, `inventory: off` (or equivalent disabled indicator), the draft count, and active placement identifier in client chat without requiring server operator permissions

### Requirement: Runtime MCP server start and stop commands
The system SHALL provide `/archica mcp start [port]` and `/archica mcp stop` client commands so players can stop, start, or rebind the embedded loopback MCP server on a valid port (`1..65535`) without restarting Minecraft.

#### Scenario: Stopping and restarting the MCP server on a new port
- **WHEN** the player runs `/archica mcp stop` followed by `/archica mcp start 25590`
- **THEN** the existing server stops cleanly, a new server binds to `http://127.0.0.1:25590/mcp`, and client chat confirms the active endpoint

#### Scenario: Invalid port argument fails cleanly in chat
- **WHEN** the player runs `/archica mcp start 70000`
- **THEN** the command prints an error feedback message in chat without crashing the client or replacing a running server

### Requirement: Quick hologram placement clearance command
The system SHALL provide `/archica clear <blueprint_id>` and `/archica clear all` client commands to remove active `archica:` Litematica placements directly from in-game chat while preserving `.litematic` files on disk.

#### Scenario: Clearing all active Archica placements from chat
- **WHEN** the player runs `/archica clear all` while two `archica:` placements are active in Litematica
- **THEN** both active `archica:` placements are removed from Litematica, their `.litematic` files remain intact, and chat reports the count of cleared placements

#### Scenario: Clearing an unknown placement reports no active match
- **WHEN** the player runs `/archica clear missing_id` when no `archica:missing_id` placement exists
- **THEN** the command completes gracefully and informs the player that no active placement was found

### Requirement: Placement lifecycle player feedback
When an MCP tool activates, replaces, or clears a Litematica placement inside a loaded world, the system SHALL post a concise single-line status message in client chat including `blueprint_id`, block count or clearance outcome, and placement origin.

#### Scenario: Compiling and activating a blueprint notifies the player
- **WHEN** an AI client calls `blueprint_compile` with `activate = true` for `oak_cottage` (`342` blocks)
- **THEN** the Minecraft client displays a single-line chat notice identifying `oak_cottage`, its block count, and whether an existing hologram was replaced

### Requirement: Player opt-in inventory tracking command
The system SHALL disable player inventory reading and survival material checking by default on every Minecraft client startup (in-memory only, resetting to `off` on restart), and SHALL provide `/archica inventory`, `/archica inventory on`, and `/archica inventory off` client commands so players can query, enable, or disable inventory tracking from in-game chat without requiring server operator permissions.

#### Scenario: Default state on client launch ignores inventory
- **WHEN** the Minecraft client starts without any prior `/archica inventory on` command in the current session
- **THEN** inventory tracking is `off` and `/archica inventory` reports that player inventory is currently ignored

#### Scenario: Enabling and disabling inventory tracking in chat
- **WHEN** the player runs `/archica inventory on` and subsequently runs `/archica inventory off`
- **THEN** the first command enables inventory reporting in `get_build_context` and `survival_check` in `blueprint_*` tools with a chat confirmation, and the second command disables both again with a chat confirmation
