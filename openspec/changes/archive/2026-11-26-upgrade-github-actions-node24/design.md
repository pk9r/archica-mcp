# Design

## Context

GitHub Actions CI run `45753084179` on `master` passed, but emitted two deprecation annotations:
1. `Node.js 20 is deprecated` (actions targeting Node.js 20 forced onto Node.js 24: `actions/checkout@v4`, `actions/setup-java@v4`, `actions/upload-artifact@v4`, `gradle/actions/setup-gradle@v4`).
2. `setup-java v4 is deprecated` (advising migration to `v5+`).

See `proposal.md` for motivation and `specs/github-ci-release/spec.md` for requirements.

## Goals / Non-Goals

**Goals:**
- Upgrade all third-party actions in `.github/workflows/ci.yml` and `.github/workflows/release.yml` to their verified Node.js 24 (`using: node24`) major versions while preserving all existing inputs and behavior.
- Eliminate both deprecation annotations on future GitHub Actions runs.

**Non-Goals:**
- Changing workflow triggers, permissions, Java 25 toolchain, Gradle commands, tag-to-`mod_version` validation, or release artifact naming.

## Decisions

### 1. Upgrade all five workflow actions to verified `node24` major tags
Verified against each repository's latest release and `action.yml` metadata via `gh api`:

| Action | Current | Target | Verified Latest Release | Runtime |
|---|---|---|---|---|
| `actions/checkout` | `v4` | `v7` | `v7.0.1` | `node24` |
| `actions/setup-java` | `v4` | `v6` | `v6.3.0` | `node24` |
| `actions/upload-artifact` | `v4` | `v7` | `v7.0.2` | `node24` |
| `gradle/actions/setup-gradle` | `v4` | `v6` | `v6.4.1` | `node24` |
| `softprops/action-gh-release` | `v2` | `v3` | `v3.0.4` | `node24` |

- **Rationale**: Using the current `node24` major tags (`v7`/`v6`/`v3`) rather than intermediate `v5` tags clears both the `setup-java` deprecation and the Node.js 20 runner deprecation across every step in both workflows. Existing workflow inputs (`distribution`, `java-version`, `if-no-files-found`, `tag_name`, `name`, `generate_release_notes`, `prerelease`, `files`) remain present in the target `action.yml` schemas.
- **Alternative considered**: Upgrading only `actions/setup-java` to `v5` and leaving `v4` actions unchanged. Rejected because `checkout@v4`, `upload-artifact@v4`, and `setup-gradle@v4` still target Node.js 20 and would continue triggering the Node.js 20 deprecation annotation.

### 2. Include `softprops/action-gh-release` (`v2` → `v3`) in `release.yml`
- **Choice**: Upgrade `softprops/action-gh-release@v2` to `@v3` proactively even though `release.yml` has not yet run on a tag.
- **Rationale**: Prevents `release.yml` from hitting the same Node.js 20 deprecation warning on the first `v*` tag release.

## Risks / Trade-offs

- **Upstream major-version behavioral changes** → Checked `action.yml` inputs (`distribution`, `java-version`, `if-no-files-found`, `tag_name`, `prerelease`, `files`, `generate_release_notes`) directly on GitHub; CI execution will be verified live via `gh run watch` after applying and pushing to `master`.
