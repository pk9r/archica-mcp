# mcp-server Delta Specification

## ADDED Requirements

### Requirement: MCP initialization instructions and primitive schema guidance
The MCP server SHALL include concise `instructions` in its initialization metadata describing the recommended inspect-draft-compile workflow and anchor-relative coordinate convention (`+Y` up, `+Z` forward, `+X` right), and SHALL advertise detailed parameter guidance for all 11 primitive types in `blueprint_add_ops` and `blueprint_update_op` schemas.

#### Scenario: Initialize response includes workflow instructions
- **WHEN** an MCP client completes the `initialize` handshake against `/mcp`
- **THEN** the server result includes non-empty `instructions` documenting `get_build_context`, stateful blueprint ops, and local-to-world coordinate orientation

#### Scenario: Tool listing includes primitive parameter documentation
- **WHEN** an MCP client sends `tools/list`
- **THEN** the `blueprint_add_ops` and `blueprint_update_op` input schemas describe the parameter structure and required fields for all 11 supported primitive `type` variants

### Requirement: Runtime MCP server lifecycle control
The embedded MCP server controller SHALL expose thread-safe runtime `start(port?)`, `stop()`, and status inspection operations for client UX commands while enforcing loopback-only `127.0.0.1` binding, DNS-rebinding protection, and fail-silent port conflict handling.

#### Scenario: Restarting the server on a custom port preserves loopback security
- **WHEN** the runtime controller stops the running server and starts it on port `25590`
- **THEN** the server binds strictly to `127.0.0.1:25590`, keeps DNS-rebinding protection enabled, and serves all registered MCP tools
