# Spec Delta

## ADDED Requirements

### Requirement: Server-assisted scan_volume fallback for unloaded client chunks
When a client is connected to a server running `archica-mcp` and `scan_volume` targets a bounding box containing chunks not loaded on the client, the system SHALL fall back to querying the server's read-only world source for the player's current dimension over Fabric Networking while enforcing the same `13,824`-block cap and `INVALID_BOUNDS` / `VOLUME_TOO_LARGE` validation.

#### Scenario: Scanning beyond client render distance on a modded server
- **WHEN** `scan_volume` is called for a valid `<= 13,824`-block region whose chunks are loaded on the `archica-mcp` server but outside the client's loaded chunks
- **THEN** the client delegates the read-only scan to the server and returns the standard `[VOLUME]`, `[PALETTE]`, and RLE Y-slice payload

#### Scenario: Scanning unloaded chunks on an unmodded server still fails cleanly
- **WHEN** `scan_volume` is called while connected to a server without `archica-mcp` and no world/chunk context is available
- **THEN** the client evaluates locally without sending server-sync requests
