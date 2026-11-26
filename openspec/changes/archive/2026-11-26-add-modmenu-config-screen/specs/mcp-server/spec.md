# Spec Delta

## MODIFIED Requirements

### Requirement: Port configuration via JVM system property
The system SHALL resolve the startup listening port with precedence `-Darchica.mcp.port=<port>` first, then persisted client config `mcpPort`, and finally `25580`. If `-Darchica.mcp.port` is non-numeric or outside `1..65535`, the system SHALL log a warning and fall back to the configured fallback port (default `25580`).

#### Scenario: Valid custom port override
- **WHEN** the client starts with `-Darchica.mcp.port=25581`
- **THEN** the MCP server binds to `127.0.0.1:25581` and serves `/mcp` on that port

#### Scenario: Invalid custom port falls back to default
- **WHEN** the client starts with `-Darchica.mcp.port=not_a_number` or `-Darchica.mcp.port=70000` and no custom config port is set
- **THEN** the system logs a warning and falls back to binding on port `25580`

#### Scenario: Valid JVM override takes precedence over config file
- **WHEN** `config/archica-mcp.json` specifies `mcpPort = 25590` and the client starts with `-Darchica.mcp.port=25581`
- **THEN** the MCP server binds to `127.0.0.1:25581` and serves `/mcp` on that port

#### Scenario: Persisted config port used when JVM property is unset
- **WHEN** `-Darchica.mcp.port` is unset and `config/archica-mcp.json` specifies `mcpPort = 25590`
- **THEN** the MCP server binds to `127.0.0.1:25590` and serves `/mcp` on that port

#### Scenario: Invalid JVM property falls back to configured fallback port
- **WHEN** `config/archica-mcp.json` specifies `mcpPort = 25590` and the client starts with `-Darchica.mcp.port=70000`
- **THEN** the system logs a warning and falls back to binding on port `25590`

### Requirement: Fail-silent client lifecycle and graceful shutdown
The embedded MCP server SHALL start asynchronously on launch when `autoStartMcp` is enabled (default `true`), skip automatic binding when `autoStartMcp` is `false`, and shut down gracefully on client stop. Startup bind failures SHALL log a warning without crashing the Minecraft client.

#### Scenario: Port conflict on startup fails silently
- **WHEN** another process is already listening on `127.0.0.1:25580` during client initialization
- **THEN** the system catches the startup failure, logs a warning including the endpoint/port, and leaves the Minecraft client running normally

#### Scenario: Auto-start disabled skips MCP server binding at launch
- **WHEN** `config/archica-mcp.json` sets `autoStartMcp = false` and the Minecraft client initializes
- **THEN** the embedded MCP server remains stopped until the player explicitly starts it via the configuration screen or `/archica mcp start`

#### Scenario: Client shutdown stops the embedded server
- **WHEN** the Minecraft client triggers its stopping lifecycle event while the MCP server is running
- **THEN** the embedded MCP server stops gracefully and releases its bound socket
