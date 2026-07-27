# Configuration

This document is the canonical reference for environment variables and for applying configuration changes safely.

## Loading model

The Java application reads environment variables; it does not parse `.env` directly.

- `scripts/run-with-env.sh` loads the project `.env` before running a Java command.
- `scripts/install-dashboard.sh` installs a systemd service whose launcher loads `.env` on every start.
- `scripts/install-hermes.sh` copies the relevant values into the Hermes MCP server entry. Rerun it after those values change.
- `EXPENSE_ENV_FILE=/absolute/path` can select another file for one-shot launcher commands and Hermes installation. The generated dashboard unit does not persist this override; the supported systemd deployment uses the project `.env`.

The normal file is `/home/ubuntu/hermes-expense-ledger/.env`. It is ignored by Git and should have mode `600`.

## Variable reference

| Variable | Required by | Default | Validation and purpose |
| --- | --- | --- | --- |
| `EXPENSE_DISCORD_CHANNEL_ID` | MCP writes and Hermes install | None | Dedicated Discord channel; numeric snowflake, 15–22 digits |
| `EXPENSE_DISCORD_ALLOWED_USER_IDS` | Hermes install | None | Comma-separated Discord user snowflakes; source for the owner allowlist |
| `EXPENSE_BASE_CURRENCY` | All application modes | `GTQ` | ISO 4217 code with supported minor units; used only when a new movement omits currency |
| `EXPENSE_TIMEZONE` | All application modes | `America/Guatemala` | Valid IANA timezone; controls default dates, relative dates, filenames, and backup schedule |
| `EXPENSE_DB_PATH` | All persistent modes | `~/.local/share/hermes-expense-ledger/data/ledger.db` | Absolute SQLite file path |
| `EXPENSE_BACKUP_DIR` | Backup, dashboard, and MCP | `~/backups/hermes-expense-ledger` | Absolute local archive directory |
| `EXPENSE_LOG_DIR` | All persistent modes | `~/.local/state/hermes-expense-ledger/logs` | Absolute JSONL log directory |
| `EXPENSE_WEB_BIND` | Dashboard | `127.0.0.1` | Must resolve to a loopback address |
| `EXPENSE_WEB_PORT` | Dashboard | `8787` | Integer from 1 through 65535 |
| `EXPENSE_WEB_ACCESS_TOKEN` | Dashboard | None | Generated secret, at least 32 characters; placeholder values are rejected |
| `EXPENSE_BACKUP_HOUR` | Dashboard scheduler | `3` | Local hour from 0 through 23 |
| `EXPENSE_BACKUP_DAILY_RETENTION` | Backup service | `30` | Number from 1 through 3650; keeps this many newest archives |
| `EXPENSE_BACKUP_MONTHLY_RETENTION` | Backup service | `12` | Number from 0 through 1200; additionally keeps one archive per month |
| `EXPENSE_HERMES_MODEL` | Hermes installer | `gpt-5.6-luna` | Hermes model identifier used for new sessions |
| `EXPENSE_HERMES_REASONING_EFFORT` | Hermes installer | `low` | Reasoning level accepted by the installed Hermes version and selected model |

Paths accept `~` or a leading `~/`. Other tilde forms and relative paths are rejected.

## Example for the supported Guatemala deployment

```dotenv
EXPENSE_BASE_CURRENCY=GTQ
EXPENSE_TIMEZONE=America/Guatemala

EXPENSE_DISCORD_CHANNEL_ID=000000000000000000
EXPENSE_DISCORD_ALLOWED_USER_IDS=000000000000000000

EXPENSE_HERMES_MODEL=gpt-5.6-luna
EXPENSE_HERMES_REASONING_EFFORT=low

EXPENSE_DB_PATH=~/.local/share/hermes-expense-ledger/data/ledger.db
EXPENSE_BACKUP_DIR=~/backups/hermes-expense-ledger
EXPENSE_LOG_DIR=~/.local/state/hermes-expense-ledger/logs

EXPENSE_WEB_BIND=127.0.0.1
EXPENSE_WEB_PORT=8787
EXPENSE_WEB_ACCESS_TOKEN=replace-with-a-generated-token

EXPENSE_BACKUP_HOUR=3
EXPENSE_BACKUP_DAILY_RETENTION=30
EXPENSE_BACKUP_MONTHLY_RETENTION=12
```

The IDs and token above are placeholders. Never put real values into tracked examples.

## Change-impact matrix

Edit `.env`, then apply the change using this matrix:

| Changed setting | Dashboard reinstall | Hermes reinstall | Tailscale update | Additional action |
| --- | --- | --- | --- | --- |
| `EXPENSE_WEB_ACCESS_TOKEN` | Yes | No | No | Existing dashboard sessions end when the service restarts |
| `EXPENSE_WEB_BIND` | Yes | No | Usually | Only loopback values are accepted |
| `EXPENSE_WEB_PORT` | Yes | No | Yes | Point Serve to the new loopback port |
| Backup hour or retention | Yes | No | No | Scheduler recalculates its next run after restart |
| Discord channel ID | No | Yes | No | Test in the new dedicated channel |
| Allowed Discord user IDs | No | Yes | No | Also update `DISCORD_ALLOWED_USERS` in the Hermes environment |
| Hermes model or reasoning effort | No | Yes | No | New Hermes sessions use the new setting |
| Base currency or timezone | Yes | Yes | No | Existing entries are unchanged |
| Database, backup, or log path | Yes | Yes | No | Move or copy existing data first when changing paths |
| Java installation path | Yes | Yes | No | Both installers detect and persist the current Java home |

Commands referenced above:

```bash
./scripts/install-dashboard.sh
./scripts/install-hermes.sh --replace-mcp
tailscale serve --bg http://127.0.0.1:8787
```

## Discord IDs and allowlists

`EXPENSE_DISCORD_CHANNEL_ID` is enforced twice:

1. Hermes binds the `manage-expenses` skill to that channel.
2. Java rejects draft creation from every other channel.

`EXPENSE_DISCORD_ALLOWED_USER_IDS` is used by the installer, while the running Discord gateway enforces `DISCORD_ALLOWED_USERS` in the Hermes environment file.

When changing allowed users:

1. Edit `EXPENSE_DISCORD_ALLOWED_USER_IDS` in the project `.env`.
2. Run `hermes config env-path` to locate the Hermes environment file.
3. Update `DISCORD_ALLOWED_USERS` there with the same comma-separated IDs.
4. Run `./scripts/install-hermes.sh --replace-mcp`.
5. Verify `hermes gateway status` and test only with an intended account.

Do not print the complete Hermes environment file; it normally contains the Discord bot credential.

## Model and reasoning changes

Set the model and reasoning effort in `.env`, then reinstall Hermes:

```dotenv
EXPENSE_HERMES_MODEL=gpt-5.6-luna
EXPENSE_HERMES_REASONING_EFFORT=low
```

```bash
./scripts/install-hermes.sh --replace-mcp
hermes config get model.default --json
hermes config get agent.reasoning_effort --json
hermes mcp test hermes-expense-ledger
```

Model cost and supported reasoning levels are external to this repository and can change. Verify them in the installed Hermes/provider documentation before choosing a new value.

## Rotate the dashboard token

Generate a new token without printing any other environment value:

```bash
./gradlew installDist
build/install/hermes-expense-ledger/bin/hermes-expense-ledger generate-token
```

Replace only `EXPENSE_WEB_ACCESS_TOKEN` in `.env`, then run:

```bash
chmod 600 .env
./scripts/install-dashboard.sh
```

The restart removes in-memory sessions, so every browser must sign in with the new token.

## Change the dashboard port

After changing `EXPENSE_WEB_PORT`, reinstall the service and replace the Tailscale proxy target:

```bash
./scripts/install-dashboard.sh
tailscale serve --bg http://127.0.0.1:NEW_PORT
tailscale serve status
```

Keep `EXPENSE_WEB_BIND=127.0.0.1`. The application intentionally refuses a public bind address.

## Change persistent paths

Changing a path does not move existing files automatically.

- For `EXPENSE_DB_PATH`, stop the dashboard and Hermes gateway, create a verified backup, copy or restore the database to the new absolute path, update `.env`, run both installers, and verify health before removing the old copy.
- For `EXPENSE_BACKUP_DIR`, create the new directory, copy archives and `.sha256` sidecars if history should be preserved, update `.env`, and run both installers.
- For `EXPENSE_LOG_DIR`, create the new directory, update `.env`, and run both installers. Archived technical logs may be copied while services are stopped.

Use the recovery validation steps in [Operations and recovery](OPERATIONS.md) for database moves.

## Currency and timezone effects

Changing `EXPENSE_BASE_CURRENCY` affects only new drafts that omit currency. It does not convert or relabel existing entries.

Changing `EXPENSE_TIMEZONE` affects future default dates, relative-date interpretation, scheduled backup time, and backup filenames. Stored ledger dates remain unchanged.

After either change, run both installers so the dashboard and Hermes MCP process use identical values.

## Validate configuration

The most complete non-mutating application check is:

```bash
./scripts/run-with-env.sh health
```

For dashboard-only validation, the installation script also runs all tests before restarting the service. Malformed IDs, currencies, timezones, paths, numeric limits, public bind addresses, and weak dashboard tokens cause startup to fail rather than silently applying unsafe defaults.
