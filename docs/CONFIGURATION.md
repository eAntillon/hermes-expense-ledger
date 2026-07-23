# Configuration

Configuration is read from environment variables. The application does not parse `.env` itself; systemd and the supplied launch scripts load it.

| Variable | Required | Default | Purpose |
| --- | --- | --- | --- |
| `EXPENSE_DISCORD_CHANNEL_ID` | For MCP writes | None | Dedicated Discord expense channel |
| `EXPENSE_DISCORD_ALLOWED_USER_IDS` | For Hermes install | None | Comma-separated Discord user IDs |
| `EXPENSE_BASE_CURRENCY` | No | `GTQ` | ISO 4217 default currency |
| `EXPENSE_TIMEZONE` | No | `America/Guatemala` | Dates and scheduled backups |
| `EXPENSE_DB_PATH` | No | `~/.local/share/hermes-expense-ledger/data/ledger.db` | SQLite file |
| `EXPENSE_BACKUP_DIR` | No | `~/backups/hermes-expense-ledger` | Local backup directory |
| `EXPENSE_LOG_DIR` | No | `~/.local/state/hermes-expense-ledger/logs` | JSONL technical logs |
| `EXPENSE_WEB_BIND` | No | `127.0.0.1` | Dashboard bind address |
| `EXPENSE_WEB_PORT` | No | `8787` | Dashboard port |
| `EXPENSE_WEB_ACCESS_TOKEN` | For dashboard | None | Login secret; use `generate-token` |
| `EXPENSE_BACKUP_HOUR` | No | `3` | Local hour for daily backup, 0–23 |
| `EXPENSE_BACKUP_DAILY_RETENTION` | No | `30` | Daily backups retained |
| `EXPENSE_BACKUP_MONTHLY_RETENTION` | No | `12` | Monthly backups retained |
| `EXPENSE_HERMES_MODEL` | For install | `gpt-5.6-luna` | Hermes Codex model |
| `EXPENSE_HERMES_REASONING_EFFORT` | For install | `low` | Hermes reasoning effort |

Paths beginning with `~/` are expanded to the current user's home directory. Relative paths are rejected. Secrets must stay in `.env`, which is excluded from Git.

The application exits on malformed IDs, unsupported currencies, invalid timezones, insecure public dashboard binding, or missing secrets for the selected command.
