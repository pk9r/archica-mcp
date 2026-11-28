# Spec Delta

## MODIFIED Requirements

### Requirement: Client status command
The system SHALL register `/archica status` as a client-side command displaying whether the embedded MCP server is running or stopped, its loopback endpoint URL, whether player inventory tracking is enabled or disabled (`off` by default), whether blueprint tools are operating locally or synced with an `archica-mcp` server, the number of active draft sessions, and currently active `archica:` and `archica:shared:` Litematica placements.

#### Scenario: Running server status displayed in client chat
- **WHEN** the player runs `/archica status` while the MCP server is listening on `127.0.0.1:25580` with `1` draft session, `1` active placement, and default inventory tracking disabled
- **THEN** the command prints the running state, `http://127.0.0.1:25580/mcp`, `inventory: off` (or equivalent disabled indicator), sync mode (`local` or `server-synced`), the draft count, and active placement identifier in client chat without requiring server operator permissions

### Requirement: Quick hologram placement clearance command
The system SHALL provide `/archica clear <blueprint_id>` and `/archica clear all` client commands to remove active `archica:` and `archica:shared:` Litematica placements from the client directly from in-game chat while preserving `.litematic` files on disk.

#### Scenario: Clearing all active Archica placements from chat
- **WHEN** the player runs `/archica clear all` while two `archica:` or `archica:shared:` placements are active in Litematica
- **THEN** all active Archica placements are removed from the client's Litematica, `.litematic` files remain intact, and chat reports the count of cleared placements

#### Scenario: Clearing an unknown placement reports no active match
- **WHEN** the player runs `/archica clear missing_id` when no matching `archica:missing_id` or `archica:shared:missing_id` placement exists
- **THEN** the command completes gracefully and informs the player that no active placement was found
