# Spec Delta

## ADDED Requirements

### Requirement: Supported GitHub Actions runtime and non-deprecated action versions
All GitHub Actions workflows SHALL reference non-deprecated action major versions whose runtime targets Node.js 24 or newer, so workflow executions complete without Node.js 20 or deprecated action-version annotations.

#### Scenario: CI workflow runs without deprecation annotations
- **WHEN** `.github/workflows/ci.yml` executes on `ubuntu-latest`
- **THEN** the job completes without emitting Node.js 20 deprecation warnings or `setup-java` deprecation annotations

#### Scenario: Release workflow uses Node.js 24 native actions
- **WHEN** `.github/workflows/release.yml` executes on a `v*` tag
- **THEN** all referenced workflow actions execute on a Node.js 24+ runtime without Node.js 20 or `setup-java` deprecation warnings
