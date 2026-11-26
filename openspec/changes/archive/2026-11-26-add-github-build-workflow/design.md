# Design

## Context

Archica MCP is a Minecraft `26.3` Fabric + Kotlin mod built with Gradle `9.8.2`, Fabric Loom `1.19-SNAPSHOT`, Kotlin `2.4.21`, and Java toolchain `25` (see `build.gradle.kts`, `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties`). Local builds run `./gradlew test build` and output `build/libs/archica-mcp-<mod_version>.jar` and `build/libs/archica-mcp-<mod_version>-sources.jar`. Currently there is no `.github/workflows/` directory, no git tags exist, and `gradlew` is tracked in git with mode `100644`.

See `proposal.md` for motivation and `specs/github-ci-release/spec.md` for observable requirements.

## Goals / Non-Goals

**Goals:**
- Provide fast, deterministic CI verification on `ubuntu-latest` for `master` and pull requests using `gradle/actions/setup-gradle` caching (Gradle caches, Loom Minecraft/Modrinth artifacts).
- Provide a self-contained GitHub Release pipeline triggered by `v*` tags using only `GITHUB_TOKEN` (no external secrets).
- Guarantee released `.jar` filenames and `fabric.mod.json` `version` match the git tag by enforcing `tag == v<mod_version>`.

**Non-Goals:**
- Publishing to Modrinth or CurseForge (deferred to a future change when API tokens and metadata are desired).
- Running a live Minecraft client or headless LWJGL integration test inside GitHub Actions (CI runs `./gradlew test build`, covering compilation, Fabric Loom packaging, Jar-in-Jar bundling, and JUnit 5 unit tests).
- Supporting Windows/macOS CI matrix runners unless cross-platform native build issues arise later.

## Decisions

### 1. Split CI and Release into two separate workflow files
- **Choice**: Create `.github/workflows/ci.yml` (`contents: read`) and `.github/workflows/release.yml` (`contents: write`).
- **Rationale**: Separates PR/push verification from release-publishing permissions (`contents: write`), keeps GitHub branch status checks clean, and avoids conditional complexity in a single workflow.
- **Alternative considered**: One unified `build.yml` with `if: startsWith(github.ref, 'refs/tags/v')`. Rejected because it requires granting `contents: write` (or managing job-level overrides across triggers) on the same workflow file that runs on untrusted PR contexts.

### 2. Enforce `gradle.properties` (`mod_version`) as single source of truth
- **Choice**: `release.yml` strips the `v` prefix from `${GITHUB_REF_NAME}` (or `github.ref_name`) and verifies it matches `mod_version` parsed from `gradle.properties` before running `./gradlew test build`.
- **Rationale**: Prevents accidental releases where the GitHub Release tag says `v0.2.0` while `fabric.mod.json` inside the `.jar` still reports `0.1.0`. Ensures checking out any release tag locally produces the exact same versioned artifact.
- **Alternative considered**: Overriding Gradle via `-Pmod_version=<tag>`. Rejected because git history and local builds at that tag would diverge from the published jar version.

### 3. Java 25 + `gradle/actions/setup-gradle` + Gradle `--no-daemon`
- **Choice**: Use `actions/checkout@v4`, `actions/setup-java@v4` (`distribution: 'temurin'`, `java-version: '25'`), `gradle/actions/setup-gradle@v4`, and invoke `./gradlew test build --no-daemon`.
- **Rationale**: `build.gradle.kts` sets `jvmToolchain(25)` and `options.release.set(25)`. `gradle/actions/setup-gradle@v4` automatically caches `~/.gradle/caches`, wrapper distributions, and Loom's `.gradle/loom-cache` across runs, while `--no-daemon` avoids wasting runner memory on a long-lived daemon process after the single build invocation. Note that `gradle.properties` enables `org.gradle.configuration-cache=true`, which improves repeat runs when cached.
- **Alternative considered**: Generic `actions/cache@v4` with manual paths. Rejected because `setup-gradle@v4` handles Gradle/Loom lock contention and cache eviction cleanly out of the box.

### 4. Dual protection for `gradlew` executable permission
- **Choice**: Update `gradlew` in git index (`git update-index --chmod=+x gradlew` -> `100755`) AND keep `chmod +x gradlew` as an explicit step before running `./gradlew` in both workflows.
- **Rationale**: Fixing git mode resolves the root cause (`100644` -> `100755`), while `chmod +x gradlew` in the workflows guarantees resilience if future Windows edits or merges ever reset the file mode.

### 5. GitHub Release creation and asset selection
- **Choice**: Use `softprops/action-gh-release@v2` (or GitHub CLI `gh release create`) with `generate_release_notes: true` and explicit file globs:
  - `build/libs/archica-mcp-${VERSION}.jar`
  - `build/libs/archica-mcp-${VERSION}-sources.jar`
- **Rationale**: Matching exact expected filenames rather than `build/libs/*.jar` avoids accidentally uploading unintended intermediate jars if future Loom/Gradle plugins change outputs, and fails loudly if the expected artifact is missing. Mark prereleases automatically if `<VERSION>` contains `-` (e.g., `0.2.0-alpha.1`) via `prerelease` detection.

## Risks / Trade-offs

- **GitHub runner Java 25 availability / Gradle 9.8.2 + Loom snapshot network fetches** -> `actions/setup-java@v4` with `temurin` 25 resolves JDK 25 dynamically; Modrinth Maven or Fabric Maven transient outages can fail builds. Mitigation: `setup-gradle` caching reduces external downloads on warm caches; re-running failed jobs recovers from transient upstream timeouts.
- **Pre-release / `workflow_dispatch` edge cases on `release.yml`** -> Triggering `release.yml` manually via `workflow_dispatch` on `master` (not a `v*` tag) would fail the `v*` tag check unless guarded. Mitigation: Have `workflow_dispatch` validate that `github.ref` is a `refs/tags/v*` tag (or require running from a tag ref) and fail with clear instructions if invoked from a branch.
- **Configuration cache with Fabric Loom 1.19-SNAPSHOT** -> `gradle.properties` enables `org.gradle.configuration-cache=true`. Mitigation: Verify `./gradlew test build` works cleanly with configuration cache during implementation/CI verification.
