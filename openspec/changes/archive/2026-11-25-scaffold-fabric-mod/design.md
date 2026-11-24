# Design

## Context

See `proposal.md` for motivation. The local machine has Temurin JDK 25 (`C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot`) on `PATH` (though `JAVA_HOME` is currently unset in the shell), Git `2.54.0`, and no global `gradle` binary. The workspace (`D:\source\archica-mcp`) already holds `README.md`, `docs/*.md`, and `openspec/`, and is not yet a Git repository.

## Goals / Non-Goals

**Goals:**
- Bootstrap a working, reproducible Gradle `9.8.2` + Fabric Loom `1.19-SNAPSHOT` + Kotlin `2.4.21` build targeting unobfuscated Minecraft `26.3` on Java `25`.
- Establish clean `splitEnvironmentSourceSets()` separation between pure Kotlin logic (`src/main/kotlin`) and Minecraft/Litematica client code (`src/client/kotlin`).
- Verify dependency resolution and compilation for Fabric API, Fabric Language Kotlin, MaLiLib, Litematica, Ktor CIO, and `io.modelcontextprotocol:kotlin-sdk` with `./gradlew build` and `./gradlew test`.

**Non-Goals:**
- Implementing MCP tools, world scanning, blueprint rasterization, `.litematic` writing, or Litematica placement logic (deferred to `archica-mcp-core`).
- Modifying the user's external PrismLauncher `26.3` mods directory automatically (instructions/notes only if needed).

## Decisions

### 1. Bootstrap Wrapper & Config from `FabricMC/fabric-example-mod` (`26.3`)
- **Decision**: Clone `FabricMC/fabric-example-mod` branch `26.3` into a temporary directory, discard its `.git` folder, copy `gradle/wrapper/*`, `gradlew`, `gradlew.bat`, `.gitattributes`, and `.gitignore` into `D:\source\archica-mcp`, initialize a fresh Git repository, and author Kotlin DSL Gradle files (`settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`).
- **Rationale**: Minecraft `26.3` requires Gradle `9.8.2` and unobfuscated Loom `1.19-SNAPSHOT` (`net.fabricmc.fabric-loom`). Borrowing the official wrapper avoids needing a global Gradle install and prevents overwriting existing `README.md`, `docs/`, and `openspec/`.
- **Alternative Considered**: Installing Gradle globally via `winget` and running `gradle init`. Rejected because Fabric's official wrapper jar and properties already match Loom `1.19`.

### 2. Unobfuscated `26.3` + Kotlin DSL (`build.gradle.kts`)
- **Decision**: Use `id("net.fabricmc.fabric-loom")`, `kotlin("jvm")`, and `kotlin("plugin.serialization")` in `build.gradle.kts`, with standard `implementation(...)` dependencies (no `mappings` block, no `modImplementation` remap configuration), targeting Java `25` (`jvmToolchain(25)`).
- **Rationale**: Matches Minecraft `26.3`'s unobfuscated distribution and provides typed Gradle DSL autocomplete for Kotlin, Ktor, and MCP dependencies.

### 3. `splitEnvironmentSourceSets()` with Client-Only `fabric.mod.json`
- **Decision**: Keep `loom { splitEnvironmentSourceSets() }` so `src/main/kotlin/com/archica/mcp/` holds Minecraft-independent code (blueprint/primitives/MCP modules) and `src/client/kotlin/com/archica/mcp/` holds `ArchicaMcpClient` (`ClientModInitializer`) and Minecraft/Litematica adapters, while `fabric.mod.json` declares `"environment": "client"` and uses `"adapter": "kotlin"`.
- **Rationale**: Pure Kotlin modules can be unit-tested rapidly with `./gradlew test` without booting a Minecraft client, while client-only mod packaging prevents accidental server loading.

### 4. Dependency Resolution & Jar-in-Jar Strategy
- **Decision**: Add `https://maven.fabricmc.net/` (in `settings.gradle.kts` `pluginManagement` + repositories) and `https://api.modrinth.com/maven` + `mavenCentral()` in `build.gradle.kts`. Use `include(...)` selectively for `io.modelcontextprotocol:kotlin-sdk` and Ktor CIO artifacts not already provided by `fabric-language-kotlin`.
- **Rationale**: `fabric-language-kotlin` already bundles Kotlin stdlib, `kotlinx-coroutines`, and `kotlinx-serialization` at runtime; duplicating them via `include(...)` causes classpath conflicts, whereas Ktor CIO and MCP SDK are absent and must be bundled in the mod jar.

## Risks / Trade-offs

- **Transitive Jar-in-Jar Overlap (`include`)**: `kotlin-sdk` / Ktor bring transitive dependencies (`kotlinx-coroutines`, `kotlinx-serialization`, `slf4j`) that overlap with `fabric-language-kotlin` or Minecraft -> Mitigation: Inspect `./gradlew dependencies` during scaffolding and exclude/avoid re-including libraries already supplied by `fabric-language-kotlin` and Minecraft.
- **Modrinth Maven Coordinates for `26.3`**: Modrinth Maven version strings for `litematica` and `malilib` must match exact published tags for `26.3` -> Mitigation: Query `https://api.modrinth.com/v2/project/{litematica,malilib}/version` during scaffolding if Gradle fails to resolve a version tag.
- **Unset `JAVA_HOME` in Shell**: `gradlew.bat` relies on `JAVA_HOME` or `java` on `PATH` -> Mitigation: Document/verify running with `JAVA_HOME` pointing to `C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot` (or setting it in the build command).
