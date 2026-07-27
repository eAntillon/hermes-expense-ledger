# Hermes Expense Ledger

A private-by-default personal expense ledger for Hermes Agent. Natural-language Discord messages become validated drafts, require an explicit review step, and are recorded in SQLite only after confirmation. The same drafts and confirmed movements are available through an authenticated local dashboard.

The language model proposes data; the Java application is the authority. Java validates channel identity, amounts, currencies, dates, relationships, idempotency, and state transitions before any financial write.

## Return-to-project checklist

Start here when returning to the server after some time:

```bash
cd /home/ubuntu/hermes-expense-ledger

git status --short --branch
./scripts/run-with-env.sh health
hermes gateway status
hermes mcp test hermes-expense-ledger
systemctl --user status hermes-expense-dashboard.service
tailscale serve status
```

A healthy deployment has all of the following:

- `health` returns `"status":"healthy"`, `"sqlite_integrity":"ok"`, and `"migrations_current":true`.
- The Hermes gateway and dashboard service are `active (running)`.
- Hermes discovers ten `hermes-expense-ledger` MCP tools.
- Tailscale reports the dashboard URL as `tailnet only`.

The ignored `.env` file contains the local configuration and dashboard login token. Never commit it or paste its contents into chat, issues, or logs.

## Daily use

Write one movement per message in the configured Discord expense channel. The bot does not require a mention in that channel.

```text
compra pollo 40
salida comer mc 140.1
gasto gas 321
prestamo Ricardo 3400
devolucion tienda 25
gasto hotel 80 USD
```

Hermes returns a canonical preview. Reply in a later message with an instruction such as:

```text
confirmar
cambiar el monto a 135
cancelar
```

Creating a draft never records the movement by itself. Confirmation must happen after the preview. Confirmed entries cannot currently be edited or deleted through Discord or the dashboard.

Useful questions include:

```text
cuanto gaste este mes
mostrar mis ultimos gastos
quienes me deben y cuanto
mostrar movimientos en USD
hay borradores pendientes
```

For a loan payment, first identify the open loan so the repayment can reference its ledger entry ID. Amounts are always positive; the movement type determines whether the value is an expense, refund, loan, or loan payment. Currencies are reported separately and are never converted automatically.

For complete examples and review behavior, see [Daily usage](docs/USAGE.md).

## Dashboard

Run the following command to discover the private URL:

```bash
tailscale serve status
```

Open the HTTPS URL from a device connected to the same tailnet and sign in with `EXPENSE_WEB_ACCESS_TOKEN` from `.env`. The dashboard can:

- show totals grouped by currency;
- show open receivables and recent confirmed entries;
- show the original Discord text;
- edit, confirm, or cancel pending drafts; and
- show the latest local backup state.

The Java server remains bound to `127.0.0.1`. Tailscale Serve provides private HTTPS access; Funnel must remain disabled.

## Backups and logs

The dashboard process creates one verified local backup every day at `EXPENSE_BACKUP_HOUR` in the configured Guatemala timezone. Create an additional backup at any time with:

```bash
./scripts/run-with-env.sh backup
```

Default storage locations are:

| Data | Default location | Retention |
| --- | --- | --- |
| SQLite database | `~/.local/share/hermes-expense-ledger/data/ledger.db` | Until explicitly restored or removed |
| Verified backups | `~/backups/hermes-expense-ledger/` | 30 newest plus one per month for 12 months |
| Technical JSONL logs | `~/.local/state/hermes-expense-ledger/logs/` | 30 days, capped at 512 MiB |
| Original messages and audit events | Inside SQLite | Indefinite |

Local backups do not protect against loss of the entire server. Off-site backup is not implemented yet.

## Update the application

Use this safe sequence for normal Git updates:

```bash
cd /home/ubuntu/hermes-expense-ledger

./scripts/run-with-env.sh health
./scripts/run-with-env.sh backup
git status --short
git pull --ff-only

./scripts/install-dashboard.sh
./scripts/install-hermes.sh --replace-mcp

./scripts/run-with-env.sh health
hermes mcp test hermes-expense-ledger
systemctl --user is-active hermes-expense-dashboard.service
hermes gateway status
```

Stop before `git pull` if `git status --short` shows tracked local changes. `.env` is ignored and is not replaced by Git. Database migrations run automatically when the updated Java application starts; the manual backup created before the pull is the recovery point if rollback is required.

For rollback, dependency changes, Hermes upgrades, and post-update checks, follow [Upgrading](docs/UPGRADING.md).

## Change configuration

Edit `.env`, then apply only the affected integration:

| Change | Apply with |
| --- | --- |
| Dashboard token, port, or backup schedule | `./scripts/install-dashboard.sh` |
| Discord channel, Hermes model, or reasoning effort | `./scripts/install-hermes.sh --replace-mcp` |
| Base currency, timezone, database path, backup path, or log path | Run both installers |
| Allowed Discord users | Update `.env` and `DISCORD_ALLOWED_USERS` in `$(hermes config env-path)`, then run the Hermes installer |
| Tailscale URL or local dashboard port | Reapply `tailscale serve --bg http://127.0.0.1:PORT` |

The complete variable reference and change-impact matrix are in [Configuration](docs/CONFIGURATION.md).

## Project guarantees

- The model cannot write directly to the confirmed ledger.
- One Discord source message can create at most one draft.
- SQLite uses WAL mode, foreign keys, transactions, and append-only audit events.
- Money uses decimal parsing and integer minor units, never binary floating point.
- Original financial messages are retained with drafts, entries, and audit events.
- The dashboard requires a generated token, server-side session, and CSRF validation.
- Dependencies are version-locked and artifact checksums are committed.

## Documentation

Read the detailed documentation in this order:

1. [Documentation index](docs/README.md)
2. [Local deployment](docs/DEPLOYMENT.md)
3. [Configuration](docs/CONFIGURATION.md)
4. [Daily usage](docs/USAGE.md)
5. [Operations and recovery](docs/OPERATIONS.md)
6. [Upgrading](docs/UPGRADING.md)
7. [Hermes integration](docs/HERMES.md)
8. [Architecture](docs/ARCHITECTURE.md)
9. [Security](docs/SECURITY.md)
10. [Troubleshooting](docs/TROUBLESHOOTING.md)

## Development baseline

- Eclipse Temurin Java 22
- Gradle 9.6.1 wrapper
- MCP Java SDK 2.0.0
- SQLite JDBC 3.53.2.1

Run the release-grade local verification with:

```bash
./gradlew --write-locks --dependency-verification=strict clean check installDist cyclonedxBom
bash -n scripts/*.sh
git diff --check
```
