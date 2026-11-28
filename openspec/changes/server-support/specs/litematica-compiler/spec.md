# Spec Delta

## ADDED Requirements

### Requirement: Shared server placement namespace isolation
Server-synchronized schematics received by a client SHALL be written under `<schematicsBaseDir>/archica/shared/<blueprint_id>.litematic` and registered in Litematica with placement name `archica:shared:<blueprint_id>`, keeping local `archica:<blueprint_id>` schematics and placements isolated from server-shared ones.

#### Scenario: Local and shared blueprints with identical IDs do not collide
- **WHEN** a player has a local `cottage` placement (`archica:cottage`) and receives a server-shared `cottage` placement (`archica:shared:cottage`)
- **THEN** the shared file writes to `schematics/archica/shared/cottage.litematic` without overwriting `schematics/archica/cottage.litematic` and both placements remain distinct

### Requirement: Optional Litematica presence guard
When Litematica is absent on a client, non-Litematica capabilities (local MCP server, spatial context scanning, and stateful blueprint drafting) SHALL operate normally, while local placement activation/clearance reports `LITEMATICA_UNAVAILABLE` and incoming server-shared placement rendering is skipped safely without crashing.

#### Scenario: Client without Litematica initializes and drafts normally
- **WHEN** a Minecraft client starts with `archica-mcp` and without `litematica` installed
- **THEN** client initialization succeeds, `get_build_context` and `blueprint_add_ops` succeed, and receiving a server sync packet does not throw missing-class exceptions
