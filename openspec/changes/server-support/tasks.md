# Tasks

## 1. Universal Packaging & Common Core (`src/main`)

- [ ] 1.1 Update `src/main/resources/fabric.mod.json` to `"environment": "*"`, add `"main"` (`ArchicaMcpCommon`) and `"server"` (`ArchicaMcpServerMod`) entrypoints alongside `"client"` and `"modmenu"`, and verify Fabric mod metadata compiles cleanly with `./gradlew processResources`.
- [ ] 1.2 Move `ClientBlockCatalog` from `src/client` to `src/main` as `MinecraftBlockCatalog`, updating client wiring and verifying existing unit tests pass via `./gradlew test`.
- [ ] 1.3 Define Fabric Networking C2S/S2C custom payload codecs and chunked binary transfer framing (`SyncChunkAssembler` with unit tests for multi-chunk reassembly and mismatch guards) in `src/main` and register them in `ArchicaMcpCommon.onInitialize()`, verified via `./gradlew test`.

## 2. Dedicated Server Services & Commands

- [ ] 2.1 Implement read-only `ServerWorldContextSource` and `ServerThreadDispatcher` (`MinecraftServer.execute`) in `src/main`, verified by `ServerWorldContext` / `VolumeScanner` tests confirming `ServerLevel` reads never mutate blocks.
- [ ] 2.2 Implement `ArchicaMcpServerMod` (`DedicatedServerModInitializer`) hosting shared `BlueprintService` + `BlueprintDraftStore` under `<serverRoot>/schematics/archica/`, `ServerSharedPlacementManager` (tracking active shared placements restored from persisted drafts, broadcasting compiled `.litematic` chunks and clearances only to `ServerPlayNetworking.canSend` clients, and re-syncing on `ServerPlayConnectionEvents.JOIN`), verified via `./gradlew test build`.
- [ ] 2.3 Register dedicated-server OP-gated `/archica status` and `/archica clear <blueprint_id|all>` commands (clearing active shared placements on server and connected clients while keeping files on disk), verified via `./gradlew compileKotlin build`.

## 3. Client Gateway, Litematica Isolation & Fallback Scanning

- [ ] 3.1 Guard Litematica class loading in `ClientLitematicaBridge` behind `FabricLoader.getInstance().isModLoaded("litematica")`, add shared placement storage under `schematics/archica/shared/<blueprint_id>.litematic` (`archica:shared:<blueprint_id>`), and automatically clear `archica:shared:*` placements on `ClientPlayConnectionEvents.DISCONNECT`, verified via unit/compile checks (`./gradlew test build`).
- [ ] 3.2 Implement transparent client-gateway routing in `ArchicaMcpClient` (`BlueprintGateway` routing `blueprint_*` RPCs over Fabric Networking when connected to an `archica-mcp` server while staying local on singleplayer/unmodded servers, computing local opt-in `survival_check` client-side, and failing pending requests cleanly on disconnect), verified by gateway routing unit tests in `./gradlew test`.
- [ ] 3.3 Add read-only `ServerLevel` `scan_volume` fallback when target chunks are not loaded on `ClientLevel` while connected to an `archica-mcp` server, and update client `/archica status` and `/archica clear` commands to reflect sync mode and shared placements, verified via `./gradlew test build`.

## 4. End-to-End Verification & Documentation

- [ ] 4.1 Run full Gradle verification (`./gradlew test build`), validate OpenSpec (`openspec validate server-support`), and update `README.md` / `docs/architecture.md` to document server installation, client-gateway sync, and shared hologram behavior.
