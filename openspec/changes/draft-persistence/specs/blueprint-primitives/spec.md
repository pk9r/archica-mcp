# Spec Delta

## MODIFIED Requirements

### Requirement: Stateful blueprint draft session lifecycle
The system SHALL store draft blueprints keyed by `blueprint_id` (`^[a-z0-9_-]{1,48}$`), holding `origin`, `facing` (`NORTH`, `SOUTH`, `EAST`, `WEST`, default `NORTH`), ordered operations, and a monotonic counter (`op_1`, `op_2`, ...). Draft sessions SHALL persist across restarts and reload on startup. Duplicate creation with `overwrite=false` SHALL fail with `BLUEPRINT_EXISTS`, while `overwrite=true` resets the session and replaces its persisted draft.

#### Scenario: Creating and mutating a draft session with monotonic op IDs
- **WHEN** a session `oak_cottage` is created, two operations are added (`op_1`, `op_2`), `op_1` is removed, and a third operation is added
- **THEN** the newly added operation receives `op_3` rather than reusing `op_1`, and `op_2` remains ahead of `op_3` in evaluation order

#### Scenario: Duplicate blueprint creation rejected when overwrite is false
- **WHEN** `blueprint_create` is invoked for an already active `blueprint_id` with `overwrite=false` (or omitted)
- **THEN** the operation fails with `BLUEPRINT_EXISTS` and leaves the existing draft session untouched

#### Scenario: Reloaded session preserves monotonic op counter across restarts
- **WHEN** `oak_cottage` has operations up to `op_3`, the application restarts and reloads drafts from disk, and a new operation is added
- **THEN** the new operation receives `op_4` and all prior operations (`op_1..op_3`) remain intact in order

## ADDED Requirements

### Requirement: Atomic sidecar draft persistence on session mutations and compilation
When draft persistence is enabled, creating, mutating, compiling, or clearing placement of a blueprint SHALL atomically write its draft state (`origin`, `facing`, `next_op_number`, ordered operations, and latest placement metadata) to `<schematicsBaseDir>/archica/<blueprint_id>.draft.json`. Clearing placement with `delete_file = true` SHALL delete the `.litematic` file while keeping `<blueprint_id>.draft.json`.

#### Scenario: Mutations persist immediately to sidecar draft file
- **WHEN** a draft session `oak_cottage` is created and updated with a `fill_box` operation
- **THEN** `<schematicsBaseDir>/archica/oak_cottage.draft.json` exists on disk and contains the session `origin`, `facing`, `next_op_number`, and ordered operation list

#### Scenario: Clearing placement keeps draft history intact
- **WHEN** `blueprint_clear_placement` is called for `oak_cottage` with `delete_file = true`
- **THEN** `oak_cottage.litematic` is deleted while `oak_cottage.draft.json` remains on disk and `oak_cottage` stays available via `blueprint_list` and `blueprint_inspect`

### Requirement: Resilient startup draft reload
On startup initialization, the system SHALL scan the configured drafts directory for `*.draft.json` files, validate `blueprint_id` and operations, and restore valid drafts into the active session store. Malformed JSON, mismatched filenames, invalid `blueprint_id` values, or invalid operations in one draft file SHALL log a warning and skip that file without failing startup or blocking other valid drafts.

#### Scenario: Valid drafts reload while corrupted file is skipped safely
- **WHEN** the drafts directory contains a valid `oak_cottage.draft.json` and a corrupted `broken.draft.json` at startup
- **THEN** startup succeeds, logs a warning for `broken.draft.json`, and restores `oak_cottage` into `blueprint_list` and `blueprint_inspect`
