# Spec Delta

## ADDED Requirements

### Requirement: In-game build phase navigation and view mode commands
The system SHALL extend `/archica` client commands with `/archica phase` subcommands so players can list build phases for an active phased blueprint, jump to a phase (`/archica phase <index|next|prev>`), and switch view mode (`/archica phase mode <isolated|cumulative|all>`) from chat without requiring server operator permissions.

#### Scenario: Stepping to next phase and switching mode via chat commands
- **WHEN** a player with an active 4-phase placement `oak_cottage` at phase `1` runs `/archica phase next` and then `/archica phase mode cumulative`
- **THEN** phase `2` becomes active, view mode changes to `CUMULATIVE` (showing phases `1` and `2`), and client feedback confirms the current phase title and block count

#### Scenario: Running phase commands on an unphased blueprint informs the player
- **WHEN** a player runs `/archica phase next` when the active placement has no semantic phases
- **THEN** the command completes gracefully and informs the player that the active blueprint has no build phases

### Requirement: Phase navigation keybinds and ActionBar notices
The system SHALL register optional Minecraft Controls keybinds (unbound by default) for `Next Build Phase`, `Previous Build Phase`, and `Toggle Phase View Mode` (toggling between `ISOLATED` and `CUMULATIVE`). Triggering phase navigation or view mode switching in-world SHALL update the Litematica placement immediately and display a concise single-line notice on the player ActionBar showing blueprint id, phase position/title, view mode, and phase block count.

#### Scenario: Pressing Toggle Phase View Mode keybind flips between Isolated and Cumulative
- **WHEN** a player presses the bound `Toggle Phase View Mode` key while at phase `2` in `ISOLATED` mode
- **THEN** the active hologram updates to `CUMULATIVE` (phases `1..2`) and the ActionBar displays the updated phase title and `Cumulative` mode without spamming chat history

### Requirement: In-screen phase selector and view mode toggle button
The Archica MCP client UI SHALL provide build-phase controls when a loaded world has phased blueprints, displaying the selected blueprint's ordered phase list, previous/next phase buttons, a dedicated toggle button (`CycleButton`) switching between `Isolated` (`== k`), `Cumulative` (`1..k`), and `All`, and the active phase's description and required materials (with Survival missing counts when inventory tracking is enabled).

#### Scenario: Toggling display mode from the Archica UI screen
- **WHEN** the player opens the Archica configuration/phase controls while a 3-phase blueprint is active at phase `2` and clicks the View Mode toggle button from `Isolated` to `Cumulative`
- **THEN** the UI updates immediately, the Litematica placement enables subregions `1` and `2`, and the change persists to the blueprint draft state
