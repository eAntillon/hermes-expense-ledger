# Hermes Integration

This document explains the boundary between Hermes Agent and the Java ledger. Use [Local deployment](DEPLOYMENT.md) for the installation sequence and [Daily usage](USAGE.md) for user-facing behavior.

## Responsibilities

Hermes owns:

- the Discord gateway and bot credential;
- the global Discord user allowlist;
- channel-specific skill selection;
- model invocation and structured MCP arguments;
- source channel and Discord message context; and
- conversation/session state.

The Java application owns:

- strict MCP tool schemas;
- channel revalidation;
- financial and relationship validation;
- draft lifecycle and confirmation rules;
- idempotency and database transactions;
- reporting, health, backups, and audit persistence.

The model is not trusted as a database writer. It can request Java tools, but Java decides whether every request is valid.

## Managed files and settings

The installer operates on these local resources:

| Resource | Purpose |
| --- | --- |
| `skills/manage-expenses/` | Git-managed skill source |
| `~/.hermes/skills/manage-expenses` | Symlink to the Git-managed skill |
| `~/.hermes/config.yaml` | Model defaults, Discord channel binding, free-response channel, MCP server entry |
| `~/.hermes/config.yaml.expense-ledger.bak` | Last pre-configuration YAML backup |
| `$(hermes config env-path)` | Discord credential and global `DISCORD_ALLOWED_USERS` |

The project installer never writes secrets into Git. The MCP server entry receives only locale, channel, application paths, and Java home; it does not need the Discord bot token or dashboard access token.

## Installation and reinstallation

```bash
./scripts/install-hermes.sh --replace-mcp
```

The script is non-interactive and safe to rerun. `--replace-mcp` removes a stale entry before discovery and registration.

The Java configurator edits Hermes YAML as structured data. It preserves unrelated channel bindings and free-response channels, replaces only the configured expense-channel binding, writes atomically, and preserves file permissions.

## Channel behavior

The configured Discord channel receives two related Hermes settings:

1. `channel_skill_bindings` loads `manage-expenses` for the channel.
2. `free_response_channels` allows expense messages without mentioning the bot.

Java separately requires the same `EXPENSE_DISCORD_CHANNEL_ID` on every draft-creation request. A model or integration error cannot redirect a financial write from an unrelated Discord channel.

Inspect the structured binding:

```bash
hermes config get platforms.discord.channel_skill_bindings --json
hermes config get platforms.discord.free_response_channels --json
```

Expected binding shape:

```json
[
  {
    "id": "configured-channel-id",
    "skills": ["manage-expenses"]
  }
]
```

Other bindings may also exist and should remain present.

## User allowlist

The Hermes Discord gateway enforces `DISCORD_ALLOWED_USERS` from its environment file. The project `.env` stores the intended IDs as `EXPENSE_DISCORD_ALLOWED_USER_IDS`, but the running gateway must contain the same values.

Locate the Hermes environment without printing it:

```bash
hermes config env-path
```

After changing users, synchronize both files and rerun the installer. The Java service does not receive or store the Discord bot token.

## Model configuration

The installer applies:

```text
EXPENSE_HERMES_MODEL -> model.default
EXPENSE_HERMES_REASONING_EFFORT -> agent.reasoning_effort
```

These are Hermes global defaults, not channel-local values. A change may affect other conversations served by the same Hermes instance. Channel-specific behavior comes from the skill binding, not a channel-specific model override.

Start a new session after changing the model, reasoning effort, or skill content.

## MCP transport

Hermes launches:

```text
build/install/hermes-expense-ledger/bin/hermes-expense-ledger mcp
```

The transport is STDIO. MCP protocol messages use standard output; application logs use standard error and the JSONL log file. Writing arbitrary diagnostics to standard output would corrupt the protocol.

The installer records an explicit `JAVA_HOME` in the MCP environment so gateway startup does not depend on the interactive shell's `PATH`.

## Tool catalog

Hermes should discover exactly ten tools:

| Tool | Purpose | Mutating |
| --- | --- | --- |
| `expense_draft_create` | Validate one Discord message and create a pending draft | Yes |
| `expense_draft_edit` | Replace proposed fields of a pending draft | Yes |
| `expense_draft_confirm` | Atomically create the ledger entry | Yes |
| `expense_draft_cancel` | Cancel a pending draft idempotently | Yes |
| `expense_list` | List confirmed entries with filters | No |
| `expense_summary` | Summarize spending, refunds, and receivables by currency | No |
| `expense_pending_list` | List drafts awaiting review | No |
| `backup_create` | Create an integrity-checked local backup | Yes |
| `backup_status` | Return the most recent backup state | No |
| `service_health` | Check SQLite and runtime health | No |

## Harness and confirmation contract

The skill instructs the model to create one draft, show Java's exact preview, and wait. It must not call `expense_draft_confirm` in the same turn as `expense_draft_create`.

Validation occurs in layers:

1. MCP JSON Schema rejects malformed tool arguments.
2. Java domain validation checks amounts, currency, date, channel, movement-specific fields, relationships, and state.
3. SQLite constraints enforce positive minor units, known enum values, foreign keys, uniqueness, and append-only audit events.

The Discord channel and message ID form the source idempotency key.

## Verification

```bash
hermes config check
hermes skills list
hermes mcp test hermes-expense-ledger
hermes config get platforms.discord.channel_skill_bindings --json
hermes config get platforms.discord.free_response_channels --json
hermes config get model.default --json
hermes config get agent.reasoning_effort --json
hermes gateway status
```

At runtime, the gateway process tree should contain the Java MCP child. Use process inspection only when diagnosing startup:

```bash
systemctl --user status hermes-gateway.service
pgrep -af 'dev.eantillon.expenseledger.Main mcp'
```

## Safe diagnostics

Review recent integration errors without printing environment values:

```bash
journalctl --user -u hermes-gateway.service -n 150 --no-pager \
  | rg -i 'mcp|discord|skill|error|failed|parked|connection closed'
```

Warnings about voice Opus support or media output mounts do not affect this text-only expense channel. MCP messages such as `parked` or `Connection closed` do affect the ledger and should be resolved using [Troubleshooting](TROUBLESHOOTING.md).

Never paste access tokens, the full Hermes environment, or real financial tool payloads into issues or chat.
