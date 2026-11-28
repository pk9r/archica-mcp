# Proposal

## Why

Archica MCP is currently packaged as a `client`-only Fabric mod (`environment: "client"`), so it cannot be installed on a dedicated Fabric server and players on the same multiplayer server cannot collaborate on shared blueprints or see each other's Litematica holograms. Enabling server installation while keeping AI connections anchored to each player's local `http://127.0.0.1:25580/mcp` client gateway allows seamless solo drafting on unmodded servers and automatic team-wide blueprint persistence and Litematica hologram sync on modded servers without exposing extra HTTP ports in Docker or Tailscale.

## What Changes

- Change mod packaging in `fabric.mod.json` to `"environment": "*"` with a `main` entrypoint (shared network payloads and common `MinecraftBlockCatalog` in `src/main`), `client` entrypoint, and `server` (`DedicatedServerModInitializer`) entrypoint so singleplayer integrated servers do not launch duplicate services.
- Introduce a Fabric Networking C2S/S2C sync protocol (`server-sync`) so that when a player's client connects to a server running `archica-mcp`, `blueprint_*` MCP calls transparently operate against the server's shared `BlueprintService` and `BlueprintDraftStore` (persisted on server disk via `draft-persistence`), while unmodded servers and singleplayer continue operating 100% locally on the client.
- Automatically broadcast compiled `.litematic` schematics and placement coordinates from the server to all connected `archica-mcp` clients with Litematica installed, activating placements under a dedicated `archica:shared:<blueprint_id>` namespace, re-syncing active shared placements on player join, and clearing `archica:shared:*` placements on client disconnect.
- Add read-only `ServerLevel` `scan_volume` fallback when connected to an `archica-mcp` server for regions outside the client's loaded chunk view, plus dedicated-server `/archica status` and `/archica clear <blueprint_id|all>` commands.
- Explicitly prohibit any direct world block placement or modification on the server (`ServerLevel` remains strictly read-only).

## Capabilities

### New Capabilities

- `server-sync`: Dedicated-server shared blueprint storage, Fabric Networking C2S/S2C protocol for client-gateway blueprint mutations, automatic team-wide Litematica hologram distribution/join-sync/disconnect-cleanup, read-only server invariant, and server `/archica` administration commands.

### Modified Capabilities

- `litematica-compiler`: Isolate server-synced schematics and placements into a `shared` namespace (`schematics/archica/shared/` and `archica:shared:<blueprint_id>`) and handle optional Litematica absence safely on clients without Litematica.
- `client-ux`: Extend `/archica status` and `/archica clear` on client and server to surface server-sync connection state and shared active placements.
- `world-context-compression`: Allow `scan_volume` on a client connected to an `archica-mcp` server to fall back to read-only `ServerLevel` scanning when target chunks are not loaded on the client.

## Impact

- **Packaging & Entrypoints**: `src/main/resources/fabric.mod.json`, `build.gradle.kts` (if source set configuration needs adjustments), new `ArchicaMcpCommon` (`ModInitializer`) and `ArchicaMcpServerMod` (`DedicatedServerModInitializer`), and moving `ClientBlockCatalog` to common `src/main` as `MinecraftBlockCatalog`.
- **Networking & Server Adapters**: New Fabric Networking payload codecs and handlers in `src/main` / `src/client`, `ServerWorldContextSource`, `ServerThreadDispatcher`, and `ServerSharedPlacementManager`.
- **Client Gateway & Litematica Guard**: `ArchicaMcpClient.kt`, `ClientLitematicaBridge.kt`, and `ArchicaClientCommands.kt` route blueprint calls through the server gateway when connected to an `archica-mcp` server and guard Litematica usage with mod-presence checks.
- **Dependencies**: Uses existing `fabric-api` (`fabric-networking-api-v1`, `fabric-lifecycle-events-v1`, `fabric-command-api-v2`); no new external libraries or Docker ports required.
