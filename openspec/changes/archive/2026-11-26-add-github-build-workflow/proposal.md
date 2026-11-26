# Proposal

## Why

Archica MCP currently relies on manual local execution of `./gradlew test build` to verify builds and produce mod `.jar` artifacts, with no automated CI checks on `master` or pull requests and no repeatable release pipeline. Adding GitHub Actions workflows automates build and unit-test verification on every change and enables reproducible, tag-driven GitHub Releases of the Fabric mod jar.

## What Changes

- Add a CI GitHub Actions workflow (`.github/workflows/ci.yml`) that runs `./gradlew test build` on pushes to `master`, pull requests targeting `master`, and manual `workflow_dispatch` runs, using Java 25 and Gradle caching, and uploads the resulting `.jar` artifacts.
- Add a Release GitHub Actions workflow (`.github/workflows/release.yml`) triggered on `v*` version tags (and optional manual `workflow_dispatch` tag builds) that validates the tag matches `mod_version` in `gradle.properties`, runs `./gradlew test build`, and creates a GitHub Release attaching both the main mod jar (`archica-mcp-<version>.jar`) and sources jar (`archica-mcp-<version>-sources.jar`).
- Ensure the Gradle wrapper script (`gradlew`, currently tracked at mode `100644` in git) is executable on Linux GitHub Actions runners (`100755` in git index and defensive `chmod +x gradlew` in workflows).

## Capabilities

### New Capabilities
- `github-ci-release`: Automated GitHub Actions CI build/test verification, artifact retention, version-tag synchronization checks, and GitHub Release publishing for the Fabric mod.

### Modified Capabilities
<!-- None: no existing capability requirements change. -->

## Impact

- Repository configuration & automation: `.github/workflows/ci.yml`, `.github/workflows/release.yml`, and git executable bit on `gradlew`.
- Build/release process: Release tags (`v*`) are constrained to match `mod_version` in `gradle.properties`, keeping local builds, `fabric.mod.json` metadata, and GitHub Release assets consistent.
- Permissions & dependencies: Uses built-in `GITHUB_TOKEN` (`contents: read` for CI, `contents: write` for Release) and standard GitHub Actions (`actions/checkout`, `actions/setup-java`, `gradle/actions/setup-gradle`, `actions/upload-artifact`, `softprops/action-gh-release` or `gh release`). No runtime mod code or Modrinth/CurseForge secrets are involved.
