# Spec Delta

## ADDED Requirements

### Requirement: Native client configuration screen and Mod Menu integration
The system SHALL provide a native Minecraft configuration screen allowing players to inspect and control Archica MCP without typing slash commands, and SHALL register an optional Mod Menu config screen entrypoint so players with Mod Menu installed can open it from the Mods list without requiring Mod Menu at runtime.

#### Scenario: Opening configuration screen from Mod Menu
- **WHEN** Mod Menu is installed and the player selects Archica MCP and clicks its Config button
- **THEN** the client opens the Archica MCP configuration screen and returns to the parent Mod Menu screen when closed

#### Scenario: Client runs normally when Mod Menu is absent
- **WHEN** the Minecraft client launches with `archica-mcp` and without Mod Menu installed
- **THEN** client initialization succeeds normally without missing-class errors or startup crashes

### Requirement: In-screen MCP server, inventory, and hologram controls
The Archica MCP configuration screen SHALL expose live MCP status, port editing (`1..65535`) with inline validation, server start/restart/stop, clipboard URL copy, session-only inventory toggle (labeled as resetting to `off` on restart), persistent preference toggles, and active placement clearance.

#### Scenario: Changing port and restarting the MCP server from the screen
- **WHEN** the player enters `25590` in the port field and triggers Apply / Restart while the MCP server is running on `25580`
- **THEN** the system persists `25590` to the client config file, restarts the embedded MCP server on `http://127.0.0.1:25590/mcp`, and updates the displayed status without restarting Minecraft

#### Scenario: Invalid port input shows inline error without stopping running server
- **WHEN** the player enters `70000` or non-numeric text in the port field and triggers Apply / Restart
- **THEN** the screen displays an inline validation error message, does not persist the invalid value, and leaves any running MCP server untouched

#### Scenario: Copying the MCP endpoint URL to clipboard
- **WHEN** the player activates the Copy MCP URL control while the configured endpoint is `http://127.0.0.1:25580/mcp`
- **THEN** the client copies `http://127.0.0.1:25580/mcp` to the system clipboard and displays visual confirmation on the screen

#### Scenario: Toggling inventory tracking from the configuration screen
- **WHEN** the player toggles Inventory Tracking to `on` in the configuration screen and closes the screen
- **THEN** `get_build_context` and `survival_check` immediately reflect the updated session-only state, while the on-disk config file does not persist inventory tracking and the next Minecraft launch resets it to `off`

#### Scenario: Clearing all active Archica placements from the configuration screen
- **WHEN** the player triggers Clear All in the configuration screen while a world is loaded with active `archica:` placements
- **THEN** all active `archica:` Litematica placements are removed (keeping `.litematic` files on disk) and the screen updates the active placement count to `0`

### Requirement: Persistent non-privacy client configuration
The system SHALL persist non-privacy client preferences (`mcpPort`, `autoStartMcp`, `showPlacementFeedback`) in a JSON file at `config/archica-mcp.json` under the Minecraft game directory, falling back cleanly to defaults (`mcpPort = 25580`, `autoStartMcp = true`, `showPlacementFeedback = true`) when the file is missing, malformed, or contains out-of-range values. Player inventory tracking state SHALL NOT be persisted to this file.

#### Scenario: Loading missing or corrupted config file falls back safely
- **WHEN** the Minecraft client starts and `config/archica-mcp.json` is absent or contains invalid JSON
- **THEN** the client logs a warning (if malformed), loads default preferences (`25580`, `autoStartMcp = true`, `showPlacementFeedback = true`), and keeps inventory tracking `off`

#### Scenario: Persisting preferences excludes inventory state
- **WHEN** the player enables inventory tracking in-session and saves config changes (`mcpPort = 25590`, `autoStartMcp = false`)
- **THEN** `config/archica-mcp.json` stores `mcpPort`, `autoStartMcp`, and `showPlacementFeedback` without writing any inventory-tracking opt-in flag

### Requirement: Fallback config screen access without Mod Menu
The system SHALL allow opening the Archica MCP configuration screen via the bare `/archica` client command (with no subcommands) and via an optional keybind registered in Minecraft Controls (unbound by default), while preserving all existing `/archica status`, `/archica mcp`, `/archica inventory`, and `/archica clear` subcommands.

#### Scenario: Running bare /archica opens the configuration screen
- **WHEN** the player runs `/archica` in client chat without subcommands
- **THEN** the client schedules opening the Archica MCP configuration screen on the client thread without error feedback

#### Scenario: Optional keybind opens the configuration screen when bound
- **WHEN** the player assigns a key in Minecraft Controls to the Archica configuration keybind and presses it outside blocking screens
- **THEN** the client opens the Archica MCP configuration screen

## MODIFIED Requirements

### Requirement: Placement lifecycle player feedback
When an MCP tool activates, replaces, or clears a Litematica placement inside a loaded world and `showPlacementFeedback` is enabled (default `true`), the system SHALL post a concise single-line status message in client chat including `blueprint_id`, block count or clearance outcome, and placement origin. When `showPlacementFeedback` is disabled in configuration, chat placement notices SHALL be suppressed.

#### Scenario: Compiling and activating a blueprint notifies the player
- **WHEN** an AI client calls `blueprint_compile` with `activate = true` for `oak_cottage` (`342` blocks) while `showPlacementFeedback` is `true`
- **THEN** the Minecraft client displays a single-line chat notice identifying `oak_cottage`, its block count, and whether an existing hologram was replaced

#### Scenario: Placement chat notice suppressed when disabled in config
- **WHEN** an AI client calls `blueprint_compile` with `activate = true` while `showPlacementFeedback` is set to `false`
- **THEN** the Litematica placement updates normally without posting a chat notice to the player
