# Tasks

## 1. Pre-Release License & Local Build Verification

- [x] 1.1 Create an MIT `LICENSE` file at the repository root (copyright `2026 Archica MCP Contributors`) matching `src/main/resources/fabric.mod.json` and verify `LICENSE` exists at the root.
- [x] 1.2 Run `.\gradlew.bat test build` locally and verify the test suite passes, `build/libs/archica-mcp-0.1.0.jar` contains `LICENSE_archica-mcp` and `fabric.mod.json` with `"version": "0.1.0"`, and `build/libs/archica-mcp-0.1.0-sources.jar` is produced.

## 2. CI Verification & Stable Tag Release

- [x] 2.1 Commit `LICENSE` to `master`, push to `origin/master`, and verify the GitHub Actions CI workflow (`.github/workflows/ci.yml`) completes with `success` via `gh run list`.
- [x] 2.2 Create and push annotated git tag `v0.1.0` (`git tag -a v0.1.0 -m "Release v0.1.0"` and `git push origin v0.1.0`) and verify `.github/workflows/release.yml` completes with `success`.

## 3. Release Verification & Notes

- [x] 3.1 Verify GitHub Release `Archica MCP v0.1.0` is published as stable (`isPrerelease: false`) with `archica-mcp-0.1.0.jar` and `archica-mcp-0.1.0-sources.jar` attached via `gh release view v0.1.0`.
- [x] 3.2 Update the `v0.1.0` GitHub Release body (if needed) to include the runtime dependency baseline (Minecraft `26.3`, Fabric Loader `>=0.20.1`, Java `25`, Fabric API `0.167.0+26.3`, Fabric Language Kotlin `1.14.2+kotlin.2.4.21`, MaLiLib `0.30.2`, Litematica `0.29.1`) and MCP endpoint (`http://127.0.0.1:25580/mcp`), and verify via `gh release view v0.1.0`.
