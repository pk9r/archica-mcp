# Tasks

## 1. Repository & Gradle Wrapper Bootstrap

- [x] 1.1 Initialize Git in `D:\source\archica-mcp` (`git init`) and copy `.gitignore`, `.gitattributes`, `gradlew`, `gradlew.bat`, and `gradle/wrapper/*` from `FabricMC/fabric-example-mod` (`26.3` branch) via a temporary clone without touching `README.md`, `docs/`, or `openspec/`; verify `git status` shows a clean repo root and `.\gradlew.bat --version` executes Gradle `9.8.2` using JDK `25`.

## 2. Kotlin DSL Build & Dependency Wiring

- [x] 2.1 Create `gradle.properties` and `settings.gradle.kts` configuring `rootProject.name = "archica-mcp"`, Fabric Maven (`https://maven.fabricmc.net/`), `minecraft_version=26.3`, `loader_version=0.20.1`, `loom_version=1.19-SNAPSHOT`, `fabric_api_version`, `fabric_kotlin_version`, `malilib_version`, `litematica_version`, `mcp_sdk_version`, and `ktor_version`; verify Gradle resolves plugin repositories cleanly.
- [x] 2.2 Create `build.gradle.kts` with `net.fabricmc.fabric-loom`, `kotlin("jvm")`, `kotlin("plugin.serialization")`, `loom { splitEnvironmentSourceSets() }`, Java/Kotlin toolchain `25`, Modrinth Maven (`https://api.modrinth.com/maven`), Fabric API, Fabric Language Kotlin, MaLiLib, Litematica, Ktor CIO, `io.modelcontextprotocol:kotlin-sdk`, JUnit/Kotlin test dependencies, and selective `include(...)` Jar-in-Jar bundling (avoiding duplicate Kotlin stdlib/coroutines/serialization); verify `.\gradlew.bat dependencies` resolves all artifacts without conflicts.

## 3. Client Mod Metadata & Skeleton Sources

- [x] 3.1 Author `src/main/resources/fabric.mod.json` with `id = "archica-mcp"`, `"environment": "client"`, Kotlin client entrypoint (`"adapter": "kotlin"`), and dependency declarations (`fabricloader >= 0.20.1`, `minecraft ~26.3`, `java >= 25`, `fabric-api`, `fabric-language-kotlin`); verify resource expansion `${version}` succeeds in `processResources`.
- [x] 3.2 Create minimal skeleton source files (`src/main/kotlin/com/archica/mcp/ArchicaModInfo.kt`, `src/client/kotlin/com/archica/mcp/ArchicaMcpClient.kt`) and a unit test smoke check (`src/test/kotlin/com/archica/mcp/ArchicaModInfoTest.kt`); verify `.\gradlew.bat test` and `.\gradlew.bat build` pass and produce the mod jar in `build/libs/`.
