# Proposal

## Why

Archica MCP v1 is tested in-game and ready for its first stable `v0.1.0` GitHub Release using the existing `.github/workflows/release.yml` pipeline (`mod_version=0.1.0`). However, `src/main/resources/fabric.mod.json` declares `"license": "MIT"` and `build.gradle.kts` configures `from("LICENSE") { rename { "${it}_$archiveBaseName" } }`, while no `LICENSE` file exists at the repository root yet, and no `v0.1.0` git tag has been pushed to publish the release.

## What Changes

- Add an MIT `LICENSE` file at the repository root (copyright `Archica MCP Contributors`) so Gradle bundles `LICENSE_archica-mcp` into the packaged mod JARs.
- Verify the local `./gradlew test build` output (`build/libs/archica-mcp-0.1.0.jar` and `build/libs/archica-mcp-0.1.0-sources.jar`) contains `LICENSE_archica-mcp` and `fabric.mod.json` with version `0.1.0`.
- Commit and push the `LICENSE` addition to `master`, confirm the CI workflow (`.github/workflows/ci.yml`) succeeds, and push the annotated git tag `v0.1.0` to trigger `.github/workflows/release.yml` for a stable (non-prerelease) GitHub Release.
- Verify the published `Archica MCP v0.1.0` GitHub Release assets and add installation/dependency requirements to the release notes if needed.

## Capabilities

### New Capabilities
<!-- None — this change adds the MIT LICENSE file and executes the existing v0.1.0 release pipeline without introducing new system capabilities. -->

### Modified Capabilities
<!-- None — existing CI/release workflow requirements in openspec/specs/github-ci-release/spec.md remain unchanged. -->

## Impact

- Added file: `LICENSE` (MIT) at the repository root.
- Packaging impact: `build/libs/archica-mcp-0.1.0.jar` and `archica-mcp-0.1.0-sources.jar` will include `LICENSE_archica-mcp`.
- Repository/GitHub impact: new commit on `master`, new annotated tag `v0.1.0`, and published GitHub Release `Archica MCP v0.1.0`.
- No changes to Kotlin source code, `gradle.properties`, `build.gradle.kts`, `fabric.mod.json`, or GitHub Actions workflow files.
