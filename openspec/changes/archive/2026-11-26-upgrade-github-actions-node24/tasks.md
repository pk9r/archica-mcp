# Tasks

## 1. Upgrade CI Workflow Actions

- [x] 1.1 Update `.github/workflows/ci.yml` to use `actions/checkout@v7`, `actions/setup-java@v6`, `gradle/actions/setup-gradle@v6`, and `actions/upload-artifact@v7`, and verify no `@v4` references or tab characters remain in `.github/workflows/ci.yml`.

## 2. Upgrade Release Workflow Actions

- [x] 2.1 Update `.github/workflows/release.yml` to use `actions/checkout@v7`, `actions/setup-java@v6`, `gradle/actions/setup-gradle@v6`, and `softprops/action-gh-release@v3`, and verify no `@v4`/`@v2` references or tab characters remain in `.github/workflows/release.yml`.

## 3. Live CI Verification

- [x] 3.1 Commit and push the workflow updates to `master` (once approved) and monitor the triggered `CI` run via `gh run watch`, verifying it passes and reports zero `Node.js 20` or `setup-java` deprecation annotations.
