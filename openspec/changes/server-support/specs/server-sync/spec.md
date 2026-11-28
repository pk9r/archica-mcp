# Spec Delta

## Purpose

Defines the `server-sync` capability for running Archica MCP on dedicated Fabric servers, routing client-gateway blueprint operations over Fabric Networking when connected to an Archica-enabled server, persisting shared drafts and schematics on the server, and automatically synchronizing Litematica holograms across connected players without ever modifying world blocks directly.

## ADDED Requirements

### Requirement: Universal client and dedicated-server mod loading
The mod package SHALL declare `"environment": "*"` with separate common, client, and dedicated-server entrypoints so dedicated Fabric servers initialize shared blueprint services without starting a duplicate embedded HTTP MCP server inside singleplayer integrated servers.

#### Scenario: Dedicated server boots without Litematica or Mod Menu
- **WHEN** a dedicated Fabric server starts with `archica-mcp` installed and without `litematica`, `malilib`, or `modmenu`
- **THEN** the server loads cleanly, initializes shared blueprint persistence and networking channels, and starts accepting player connections without opening an HTTP MCP port

#### Scenario: Singleplayer integrated server avoids duplicate MCP server startup
- **WHEN** a player launches the Minecraft client and enters a singleplayer world
- **THEN** only the client entrypoint runs the local HTTP MCP server on `127.0.0.1:25580` while the dedicated-server initializer does not run

### Requirement: Client-gateway blueprint routing to modded servers
When a player's client is connected to a server that supports `archica-mcp`, `blueprint_*` MCP calls received on the client's `http://127.0.0.1:25580/mcp` endpoint SHALL route over Fabric Networking to the server's shared blueprint session store and persistence directory. When playing singleplayer or on a server without `archica-mcp`, `blueprint_*` calls SHALL execute locally on the client as before.

#### Scenario: Creating and mutating a blueprint on a modded server persists on server disk
- **WHEN** a player connected to an `archica-mcp` server invokes `blueprint_create` and `blueprint_add_ops` through their local `127.0.0.1:25580/mcp` endpoint
- **THEN** the blueprint session and `.draft.json` file are created and persisted in the server's `schematics/archica/` directory and visible to other connected modded clients via `blueprint_list`

#### Scenario: Unmodded multiplayer server stays local to the client
- **WHEN** a player connects to a vanilla or unmodded Fabric server and calls `blueprint_create` and `blueprint_compile`
- **THEN** the draft and `.litematic` are stored locally on the player's machine and the hologram activates only for that player

### Requirement: Automatic server-to-client Litematica hologram synchronization
When `blueprint_compile` with `activate = true` or `blueprint_clear_placement` completes on an `archica-mcp` server, the server SHALL persist the compiled `.litematic` schematic and placement state on server disk and broadcast synchronization payloads to all connected `archica-mcp` clients so clients with Litematica automatically activate, replace, or clear the `archica:shared:<blueprint_id>` hologram.

#### Scenario: Compiled blueprint automatically renders on all connected modded clients
- **WHEN** Client A invokes `blueprint_compile` (`activate = true`) while Client A and Client B are connected to an `archica-mcp` server
- **THEN** the server saves `<blueprint_id>.litematic` on disk and both Client A and Client B automatically receive the schematic and activate `archica:shared:<blueprint_id>` in Litematica at the compiled `world_origin`

#### Scenario: Compiling on server succeeds even when no Litematica client is online
- **WHEN** a blueprint is compiled on the server while connected clients lack Litematica
- **THEN** the server still writes the `.litematic` and `.draft.json` files cleanly without failing with `LITEMATICA_UNAVAILABLE`

### Requirement: Join re-sync and disconnect hologram cleanup
When an `archica-mcp` client joins a modded server, the server SHALL send all currently active shared placements so the client activates those holograms automatically. When the client disconnects from the server, the client SHALL clear active `archica:shared:*` Litematica placements from memory so singleplayer or other servers are not polluted.

#### Scenario: Late-joining client receives active shared placements
- **WHEN** Client B joins an `archica-mcp` server that already has an active `oak_cottage` shared placement
- **THEN** the server sends `oak_cottage` to Client B on join and Client B automatically activates `archica:shared:oak_cottage` in Litematica

#### Scenario: Disconnecting clears server-shared holograms from client Litematica
- **WHEN** Client B disconnects from the `archica-mcp` server and enters a singleplayer world
- **THEN** `archica:shared:oak_cottage` is no longer active in Litematica while local singleplayer placements remain untouched

### Requirement: Vanilla and Litematica-optional client safety
The server SHALL check client channel capability before sending synchronization payloads so vanilla clients and clients without `archica-mcp` connect and play normally without disconnects or errors. Clients running `archica-mcp` without Litematica installed SHALL ignore incoming hologram rendering without crashing.

#### Scenario: Vanilla client joins modded server safely
- **WHEN** a vanilla Minecraft client joins a server running `archica-mcp` and a modded player compiles a shared blueprint
- **THEN** the server skips sending `archica-mcp` payloads to the vanilla client and the vanilla client stays connected without errors

### Requirement: Strictly read-only server world invariant
The dedicated-server implementation SHALL NOT place, modify, or break blocks in any server world (`ServerLevel`) and SHALL NOT expose world-editing MCP tools or commands.

#### Scenario: Server blueprint compilation never mutates world blocks
- **WHEN** `blueprint_compile` runs with `activate = true` on a modded server
- **THEN** only `.litematic` / `.draft.json` files and client Litematica holograms are updated, leaving all `ServerLevel` world blocks unchanged

### Requirement: Dedicated-server Archica management commands
The dedicated server SHALL register `/archica status` (displaying persisted shared draft count, active shared placement IDs, and connected Archica-capable players) and `/archica clear <blueprint_id|all>` (clearing active shared placements across the server and connected clients while preserving `.litematic` and `.draft.json` files).

#### Scenario: Server operator clears all shared placements
- **WHEN** `/archica clear all` runs on the dedicated server while two shared placements are active
- **THEN** both shared placements are cleared from server tracking and all connected modded clients' Litematica instances while files on server disk remain intact
