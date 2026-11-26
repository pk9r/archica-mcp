# Spec Delta

## Purpose

Defines automated GitHub Actions continuous integration (CI) build/test verification and tag-driven GitHub Release publishing for the Archica MCP Fabric mod.

## ADDED Requirements

### Requirement: Automated CI build and test verification
The system SHALL provide a GitHub Actions CI workflow triggered on pushes to `master`, pull requests targeting `master`, and manual `workflow_dispatch` events that builds the mod and executes the full unit test suite using Java 25, then uploads the built `.jar` outputs as workflow artifacts.

#### Scenario: Pull request or push to master runs build and tests
- **WHEN** a commit is pushed to `master` or a pull request targeting `master` is opened or updated
- **THEN** the CI workflow sets up Java 25 and Gradle, executes `./gradlew test build`, and fails the check if compilation or any unit test fails

#### Scenario: Successful CI run retains mod jar artifacts
- **WHEN** `./gradlew test build` completes with exit code `0` in the CI workflow
- **THEN** the workflow uploads `build/libs/*.jar` (the packaged mod jar and sources jar) as downloadable GitHub Actions artifacts

### Requirement: Linux runner compatibility for Gradle wrapper
The repository and workflows SHALL ensure `./gradlew` executes reliably on Linux GitHub Actions runners (`ubuntu-latest`) even when commits originate from Windows workstations.

#### Scenario: Gradle wrapper executes on Linux runner
- **WHEN** a workflow checks out the repository on `ubuntu-latest` and invokes `./gradlew test build`
- **THEN** the runner executes `gradlew` without a permission-denied error

### Requirement: Tag-triggered GitHub Release publishing
The system SHALL provide a GitHub Actions Release workflow triggered on pushes of version tags matching `v*` (and manual `workflow_dispatch` runs on a tag ref) that builds and tests the mod and publishes a GitHub Release with auto-generated release notes and attached mod artifacts.

#### Scenario: Pushing a matching version tag creates a GitHub Release
- **WHEN** a tag `v0.1.0` is pushed while `mod_version` in `gradle.properties` is `0.1.0`
- **THEN** the Release workflow verifies tests and build via `./gradlew test build`, creates a GitHub Release for `v0.1.0` with generated release notes, and attaches `archica-mcp-0.1.0.jar` and `archica-mcp-0.1.0-sources.jar`

### Requirement: Strict tag-to-mod-version synchronization
Before building or publishing a GitHub Release, the Release workflow SHALL verify that the tag name (with its leading `v` stripped) exactly equals `mod_version` in `gradle.properties`.

#### Scenario: Mismatched tag and mod_version aborts release
- **WHEN** a tag `v0.2.0` is pushed while `mod_version` in `gradle.properties` is `0.1.0`
- **THEN** the Release workflow fails fast with a clear version-mismatch message and does not create or publish a GitHub Release

### Requirement: Least-privilege workflow permissions
Each workflow SHALL declare explicit `GITHUB_TOKEN` permissions scoped to its minimum required access.

#### Scenario: CI workflow stays read-only while Release grants write access
- **WHEN** the CI workflow runs on a branch or pull request
- **THEN** its job token has `contents: read` only, while `contents: write` is restricted to the Release workflow
