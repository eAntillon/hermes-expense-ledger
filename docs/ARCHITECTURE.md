# Architecture

## Components

The project ships one Java application with independent runtime modes:

- `mcp`: a standard-input/output MCP server used by Hermes Agent.
- `serve`: a loopback HTTP dashboard and daily backup scheduler.
- `migrate`, `backup`, and `health`: operational commands.

Both long-running processes share one SQLite database. WAL mode permits concurrent readers and one transactional writer without a separate database service.

## Write workflow

1. Hermes loads the `manage-expenses` skill for the configured Discord channel.
2. The model maps one natural-language message to strict MCP arguments.
3. The MCP SDK validates the JSON Schema; Java performs domain validation.
4. `expense_draft_create` stores a `PENDING` draft and returns a canonical preview.
5. The user confirms, edits, or cancels in Discord or the dashboard.
6. Confirmation copies the draft into the ledger and appends an audit event in one transaction.

The source channel and message form an idempotency key. Replayed Discord events cannot create duplicate drafts.

## Data model

- `drafts`: proposed movements and their review state.
- `ledger_entries`: confirmed, voidable accounting records.
- `audit_events`: append-only state-change records with original payloads.
- `backup_runs`: backup outcome, path, size, and checksum.
- `schema_migrations`: applied migration checksums.

Money is parsed with `BigDecimal` and stored in integer minor units. Currency metadata comes from `java.util.Currency`; binary floating point is never used for money.

## Boundaries

Hermes controls allowed Discord users and channel-specific skill loading. The Java service independently rejects writes from any channel other than `EXPENSE_DISCORD_CHANNEL_ID`. The dashboard uses a constant-time token check, server-side session cookies, and CSRF tokens. It binds to `127.0.0.1` unless explicitly changed.
