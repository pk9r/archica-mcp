# Archica MCP

> **Status:** Design & Documentation Phase — the architecture and v1 MCP contracts below represent the planned design; mod source code has not been implemented yet.

**Archica MCP** is a Minecraft client-side mod (Fabric + Fabric Language Kotlin) that turns your local AI assistant (Antigravity, Claude, Cursor, or any MCP-compatible IDE) into an interactive architectural co-builder using **Litematica** holograms.

Instead of giving an AI raw world-editing commands that can grief builds or require server operator permissions, Archica MCP exposes a **localhost Streamable HTTP MCP server** directly inside the Minecraft client. The agent inspects a token-efficient summary of your surroundings, builds or refines a blueprint using high-level geometric and architectural primitives, compiles it to a standard `.litematic` file, and automatically loads the hologram into Litematica for you to preview and build.

---

## How It Works

```text
+----------------------------------+         Streamable HTTP (MCP)         +---------------------------------------+
|  Local AI Client                 | <-----------------------------------> |  Archica MCP (Fabric Client + Kotlin) |
|  (Antigravity / Claude / IDE)    |    http://127.0.0.1:25580/mcp         |                                       |
+----------------------------------+                                       |  1. Spatial Context Scanner           |
                                                                           |     (Player radius + inventory)       |
  Loop:                                                                    |  2. Stateful Blueprint Session        |
  1. Inspect player surroundings                                           |     (Anchor-relative primitives)      |
  2. Draft / refine blueprint ops                                          |  3. .litematic NBT Compiler           |
  3. Compile & preview hologram                                            |  4. Litematica Placement Bridge       |
  4. Player reviews -> AI iterates                                         +-------------------+-------------------+
                                                                                               |
                                                                                               v
                                                                           +---------------------------------------+
                                                                           |  Litematica Mod (Client Hologram)     |
                                                                           |  * Renders ghost blocks at anchor     |
                                                                           |  * Tracks materials & build layers    |
                                                                           +---------------------------------------+
```

---

## Key Capabilities (Planned v1)

- **Embedded Localhost MCP Server**: Runs directly inside the Minecraft client JVM using `io.modelcontextprotocol:kotlin-sdk` + Ktor CIO Streamable HTTP (`127.0.0.1:25580/mcp`), requiring no external runtime or separate Node/Python wrapper.
- **Concise Spatial Context**: Summarizes terrain around the player (`radius = 16` default, `33x33` XZ grid) into a compact 2D relative heightmap, surface block legend, pose/anchor suggestion, and hotbar/inventory material counts.
- **Stateful Blueprint Sessions**: Agents create named in-memory drafts (`blueprint_id`) and iteratively append, update, remove, or undo operations instead of regenerating entire builds from scratch on every revision.
- **High-Level Building Primitives**: Express structures with `fill_box`, `hollow_box`, `line`, `cylinder`, `dome`, `gable_roof`, `staircase`, `replace_box`, `clear_box`, and `repeat_pattern` using anchor-relative `(0, 0, 0)` coordinates and automatic blockstate orientation.
- **File-Backed `.litematic` Compilation & Auto-Activation**: Writes standard `.litematic` NBT files under `.minecraft/schematics/` and automatically loads/replaces the active Litematica placement so refinements update the in-world hologram without stacking duplicates.

---

## Target Dependency Baseline

Verify exact patch versions on Fabric Maven and Modrinth when scaffolding the Gradle project:

| Component | Target Version | Notes |
|---|---|---|
| Minecraft (Java Edition) | `26.3` | Latest stable release baseline |
| Fabric API | `0.167.0+26.3` | Client lifecycle, networking, commands |
| Fabric Language Kotlin | `1.14.2+kotlin.2.4.21` | Kotlin runtime & stdlib for Fabric |
| Litematica | `0.29.1` | Schematic loading & hologram rendering |
| MaLiLib | `0.30.2` | Required dependency for Litematica |
| MCP Kotlin SDK | `0.16.0` | `io.modelcontextprotocol:kotlin-sdk` |
| Ktor Server (CIO) | `3.2.3` | Embedded Streamable HTTP transport |

---

## Local MCP Client Setup (Planned)

Once the mod is installed alongside Fabric Loader, Fabric API, Fabric Language Kotlin, MaLiLib, and Litematica, launch Minecraft and join a world. By default, Archica MCP binds strictly to loopback at:

```text
http://127.0.0.1:25580/mcp
```

### Example MCP Client Configuration

For MCP clients that support Streamable HTTP servers directly:

```json
{
  "mcpServers": {
    "archica-mcp": {
      "type": "streamable-http",
      "url": "http://127.0.0.1:25580/mcp"
    }
  }
}
```

> **Note:** Exact configuration keys (`type` vs `transport`, `url` vs `serverUrl`) vary slightly by client (Antigravity, Claude Desktop, Cursor, VS Code). Only loopback hosts (`127.0.0.1`, `localhost`, `[::1]`) are permitted by default via DNS-rebinding protection.

---

## Documentation

- [Architecture & Litematica Integration](docs/architecture.md) — Embedded Ktor MCP server, thread marshalling to the Minecraft client thread, `.litematic` NBT compilation, and Litematica placement replacement.
- [Spatial Context Compression](docs/spatial-context.md) — Player-radius terrain scanning, 2D heightmap + legend format, inventory material summaries, and `scan_volume` RLE slices.
- [Stateful Blueprints & Building Primitives](docs/blueprint-primitives.md) — Session lifecycle, relative coordinates, auto-facing blockstate rules, and the v1 primitive catalog.
- [MCP Tools Reference](docs/mcp-tools.md) — Planned tool schemas, request/response payloads, survival material warnings, and error codes.

---

## Roadmap

1. **Documentation & Design Baseline** *(Current)* — Finalize architecture, spatial compression, primitives, and MCP contracts.
2. **Project Scaffolding & Embedded MCP Server** — Gradle + Fabric + Kotlin setup with Ktor CIO Streamable HTTP `/mcp` endpoint and client-thread dispatcher.
3. **Player-Radius Spatial Scanner** — World context extraction, heightmap + surface legend encoding, inventory inspection, and RLE `scan_volume`.
4. **Blueprint Session Store & Primitive Compiler** — In-memory operations, voxel rasterization, auto-orientation, and `.litematic` NBT writer.
5. **Litematica Bridge & Client UX** — Automatic schematic placement/replacement, survival material checks, and client config/commands (`/archica`).
