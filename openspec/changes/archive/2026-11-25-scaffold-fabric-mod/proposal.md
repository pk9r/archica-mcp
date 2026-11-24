# Proposal

## Why

The repository currently has documentation and OpenSpec scaffolding, but no Git repository, Gradle build configuration, or compilable Fabric mod project yet. Setting up and verifying the Minecraft `26.3` unobfuscated Fabric + Kotlin + Litematica + MCP SDK build pipeline upfront de-risks dependency integration before implementing the core features in `archica-mcp-core`.

## What Changes

- Initialize Git in `D:\source\archica-mcp` with a Fabric/Gradle/IDE `.gitignore` (preserving existing `README.md`, `docs/`, and `openspec/`).
- Bootstrap Gradle Wrapper `9.8.2` (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`) from the official `FabricMC/fabric-example-mod` `26.3` branch.
- Author Kotlin DSL Gradle configuration (`settings.gradle.kts`, `gradle.properties`, `build.gradle.kts`) using `net.fabricmc.fabric-loom` (`1.19-SNAPSHOT`), Java `25`, Kotlin `2.4.21`, `splitEnvironmentSourceSets()`, Fabric API, Fabric Language Kotlin, MaLiLib, Litematica, Ktor CIO, and `io.modelcontextprotocol:kotlin-sdk` (including Jar-in-Jar packaging configuration).
- Create client-only mod metadata (`src/main/resources/fabric.mod.json`) with `"environment": "client"` and Kotlin entrypoint adapter, plus minimal skeleton packages (`src/main/kotlin/com/archica/mcp/` and `src/client/kotlin/com/archica/mcp/`) and a unit-test smoke check (`./gradlew test` and `./gradlew build`).

## Capabilities

### New Capabilities

<!-- None: pure build-tooling and project scaffolding change (`skip_specs: true` in `.openspec.yaml`). -->

### Modified Capabilities

<!-- None: no runtime feature specs are added or modified in this scaffolding milestone. -->

## Impact

- New repository root/build files: `.gitignore`, `.gitattributes`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*`, `settings.gradle.kts`, `gradle.properties`, `build.gradle.kts`.
- New mod source/resource skeleton: `src/main/kotlin/com/archica/mcp/...`, `src/client/kotlin/com/archica/mcp/...`, `src/main/resources/fabric.mod.json`, and `src/test/kotlin/...`.
- No gameplay, MCP tool, or Litematica runtime behavior is implemented yet beyond verifying entrypoint compilation and test execution.
