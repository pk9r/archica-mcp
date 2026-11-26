# Tasks

## 1. Repository Wrapper Permissions

- [x] 1.1 Update git index mode of `gradlew` to executable (`git update-index --chmod=+x gradlew`) and verify `git ls-files --stage gradlew` reports mode `100755`.

## 2. CI Build and Test Workflow

- [x] 2.1 Create `.github/workflows/ci.yml` configured with `contents: read`, triggers for `push` (`master`), `pull_request` (`master`), and `workflow_dispatch`, Java 25 (`temurin`), `gradle/actions/setup-gradle@v4`, `chmod +x gradlew`, `./gradlew test build --no-daemon`, and `actions/upload-artifact@v4` for `build/libs/*.jar`; verify YAML structure and workflow keys are valid.

## 3. Tag-Triggered GitHub Release Workflow

- [x] 3.1 Create `.github/workflows/release.yml` configured with `contents: write` and triggers for `push` tags `v*` and `workflow_dispatch`, including a step that validates `github.ref` is a `v*` tag whose stripped version matches `mod_version` in `gradle.properties` (failing fast on mismatch).
- [x] 3.2 Add the build (`./gradlew test build --no-daemon`) and GitHub Release publishing steps to `.github/workflows/release.yml` attaching `build/libs/archica-mcp-${VERSION}.jar` and `build/libs/archica-mcp-${VERSION}-sources.jar` with auto-generated release notes and automatic prerelease detection when the version contains `-`; verify YAML structure and expected asset paths against `build/libs/` outputs.

## 4. Local Build & Workflow Verification

- [x] 4.1 Run `./gradlew test build` locally (or `.\gradlew.bat test build` on Windows) to verify the project compiles, passes all unit tests, and outputs the expected `archica-mcp-<mod_version>.jar` and `archica-mcp-<mod_version>-sources.jar` artifacts matching the workflow asset globs.
- [x] 4.2 Validate `.github/workflows/ci.yml` and `.github/workflows/release.yml` syntax (e.g., via YAML parser / `actionlint` if installed) and confirm `git status` contains only the intended workflow and `gradlew` mode changes.
