# Proposal

## Why

GitHub Actions CI run `45753084179` reported two deprecation annotations on `ubuntu-latest`: Node.js 20 is deprecated (`actions/checkout@v4`, `actions/setup-java@v4`, `actions/upload-artifact@v4`, and `gradle/actions/setup-gradle@v4` are being forced onto Node.js 24) and `setup-java v4` is deprecated. Upgrading the workflow actions in `.github/workflows/ci.yml` and `.github/workflows/release.yml` to their Node.js 24 native major versions resolves both warnings before runner support is removed and keeps release automation consistent with CI.

## What Changes

- Upgrade GitHub Actions dependencies in `.github/workflows/ci.yml` and `.github/workflows/release.yml` to Node.js 24 native major versions:
  - `actions/checkout@v4` → `actions/checkout@v7`
  - `actions/setup-java@v4` → `actions/setup-java@v6`
  - `gradle/actions/setup-gradle@v4` → `gradle/actions/setup-gradle@v6`
  - `actions/upload-artifact@v4` → `actions/upload-artifact@v7` (in `ci.yml`)
  - `softprops/action-gh-release@v2` → `softprops/action-gh-release@v3` (in `release.yml`, so the release workflow also has zero Node.js 20 actions)
- Add a requirement to `github-ci-release` specifying that workflow runs use non-deprecated action versions targeting Node.js 24+ runtimes with zero Node.js 20 or `setup-java` deprecation annotations.

## Capabilities

### New Capabilities
<!-- None -->

### Modified Capabilities
- `github-ci-release`: Add runtime currency requirement so workflows run natively on Node.js 24+ without Node.js 20 or deprecated `setup-java` annotations.

## Impact

- Affected files: `.github/workflows/ci.yml` and `.github/workflows/release.yml`.
- No changes to mod Kotlin/Java source code, Gradle build configuration, or release tag behavior.
