# Operations and Recovery

This document owns runtime health, service control, backups, logs, database maintenance, and recovery. Use [Upgrading](UPGRADING.md) for Git or dependency changes.

## Routine health check

Run this after server maintenance, configuration changes, upgrades, or unexplained Discord behavior:

```bash
cd /home/ubuntu/hermes-expense-ledger

./scripts/run-with-env.sh health
systemctl --user is-active hermes-expense-dashboard.service
hermes gateway status
hermes mcp test hermes-expense-ledger
tailscale serve status
```

Healthy application output includes:

```json
{
  "status": "healthy",
  "sqlite_integrity": "ok",
  "migrations_current": true
}
```

The same response includes database location, base currency, timezone, and latest backup state. The command exits non-zero when health is not `healthy`.

## Service ownership

| Process | Supervisor | Purpose |
| --- | --- | --- |
| Hermes gateway | systemd user service managed by `hermes gateway` | Discord connection, owner allowlist, skill selection, model sessions |
| Java MCP server | Hermes gateway | Ten financial and operational MCP tools over STDIO |
| Java dashboard | `hermes-expense-dashboard.service` | Private web UI, health endpoint, daily backup scheduler |
| Tailscale Serve | `tailscaled` | Tailnet-only HTTPS reverse proxy to loopback port 8787 |

## Dashboard service commands

```bash
systemctl --user status hermes-expense-dashboard.service
systemctl --user restart hermes-expense-dashboard.service
systemctl --user stop hermes-expense-dashboard.service
systemctl --user start hermes-expense-dashboard.service
journalctl --user -u hermes-expense-dashboard.service -n 100 --no-pager
journalctl --user -u hermes-expense-dashboard.service -f
```

The rendered unit is:

```text
~/.config/systemd/user/hermes-expense-dashboard.service
```

Do not edit the rendered unit directly. Change the tracked template or environment, then run `./scripts/install-dashboard.sh`.

## Hermes service commands

```bash
hermes gateway status
hermes gateway restart
hermes gateway stop
hermes mcp test hermes-expense-ledger
journalctl --user -u hermes-gateway.service -n 100 --no-pager
journalctl --user -u hermes-gateway.service -f
```

Use `hermes gateway restart` after changing the Hermes environment. Use `./scripts/install-hermes.sh --replace-mcp` when the Java distribution, channel binding, model, paths, or MCP environment changed.

## Tailscale Serve operations

Inspect the private route:

```bash
tailscale status
tailscale serve status
```

Reapply the default route:

```bash
tailscale serve --bg http://127.0.0.1:8787
```

Disable dashboard publication without stopping the local Java service:

```bash
tailscale serve --https=443 off
```

Supported output says `tailnet only`. Do not use Funnel.

## Backups

### Manual backup

```bash
./scripts/run-with-env.sh backup
```

The command:

1. records a running backup in SQLite;
2. creates a consistent snapshot with SQLite `VACUUM INTO`;
3. runs `PRAGMA integrity_check` on the snapshot;
4. compresses it as `ledger-YYYYMMDD-HHMMSS-ID.db.gz`;
5. atomically moves the completed archive into place;
6. calculates SHA-256;
7. writes a matching `.sha256` sidecar; and
8. records success, path, checksum, and size in SQLite.

Partial archives are removed after failures. A successful command prints JSON containing the archive path and checksum.

### Scheduled backup

The dashboard schedules a backup every day at `EXPENSE_BACKUP_HOUR` in `EXPENSE_TIMEZONE`. The default is 03:00 in `America/Guatemala`.

The scheduler exists inside the dashboard process. No scheduled backup runs while that service is stopped. After a restart, the next run is recalculated for the next configured local hour.

### Retention

Retention keeps both:

- the newest `EXPENSE_BACKUP_DAILY_RETENTION` archives; and
- one archive from each of the newest `EXPENSE_BACKUP_MONTHLY_RETENTION` represented months.

The default is 30 newest archives plus one per month for 12 months. When an archive expires, its `.sha256` sidecar is removed with it.

### Verify an archive

```bash
cd "$HOME/backups/hermes-expense-ledger"
sha256sum --check ledger-YYYYMMDD-HHMMSS-ID.db.gz.sha256
gzip --test ledger-YYYYMMDD-HHMMSS-ID.db.gz
```

Both commands must succeed before using an archive for recovery or off-site copying.

## Database and migrations

Default database:

```text
~/.local/share/hermes-expense-ledger/data/ledger.db
```

Application startup automatically runs checksum-protected migrations and applies them transactionally. The migration runner does not create a backup; operators must run `./scripts/run-with-env.sh backup` before installing code that may contain new migrations.

Manual migration and health commands are available for maintenance:

```bash
./scripts/run-with-env.sh migrate
./scripts/run-with-env.sh health
```

Do not edit financial tables with the `sqlite3` CLI. Direct changes bypass domain validation and application audit events. The `audit_events` table has database triggers that reject update and delete operations.

SQLite runs in WAL mode. Always stop both Java processes before moving or replacing the database and account for `ledger.db-wal` and `ledger.db-shm` sidecars.

## Restore a backup

The following procedure is intentionally conservative. It keeps the previous active database until the restored copy is verified.

### 1. Select and verify the archive

```bash
archive="$HOME/backups/hermes-expense-ledger/ledger-YYYYMMDD-HHMMSS-ID.db.gz"
sha256sum --check "${archive}.sha256"
gzip --test "${archive}"
```

Use the exact archive path; do not restore through a wildcard.

### 2. Stop all database users

```bash
systemctl --user stop hermes-expense-dashboard.service
hermes gateway stop
```

Confirm that no ledger Java process remains:

```bash
pgrep -af 'dev.eantillon.expenseledger.Main' || true
```

### 3. Decompress into an isolated directory

```bash
recovery_dir="$(mktemp -d)"
gzip --decompress --stdout "${archive}" > "${recovery_dir}/ledger.db"
chmod 600 "${recovery_dir}/ledger.db"
```

### 4. Validate the candidate database

Load the normal environment, override only the candidate database path, and run health directly:

```bash
set -a
source ./.env
set +a

EXPENSE_DB_PATH="${recovery_dir}/ledger.db" \
  build/install/hermes-expense-ledger/bin/hermes-expense-ledger health
```

Continue only when SQLite integrity is `ok` and migrations are current. If an older archive requires a new migration, copy the candidate first so the original archive remains unchanged.

### 5. Preserve the current database and install the candidate

The default paths below must be changed if `EXPENSE_DB_PATH` is customized:

```bash
active_db="$HOME/.local/share/hermes-expense-ledger/data/ledger.db"
recovery_stamp="$(date -u +%Y%m%dT%H%M%SZ)"

mv -- "${active_db}" "${active_db}.pre-restore-${recovery_stamp}"
if [[ -f "${active_db}-wal" ]]; then
  mv -- "${active_db}-wal" "${active_db}-wal.pre-restore-${recovery_stamp}"
fi
if [[ -f "${active_db}-shm" ]]; then
  mv -- "${active_db}-shm" "${active_db}-shm.pre-restore-${recovery_stamp}"
fi
mv -- "${recovery_dir}/ledger.db" "${active_db}"
chmod 600 "${active_db}"
```

These moves are recoverable. Do not delete the `pre-restore` files until the application has been verified and another successful backup exists.

### 6. Restart and verify

```bash
systemctl --user start hermes-expense-dashboard.service
hermes gateway restart

./scripts/run-with-env.sh health
hermes mcp test hermes-expense-ledger
curl --fail http://127.0.0.1:8787/healthz
./scripts/run-with-env.sh backup
```

Open the dashboard and inspect recent entries and receivable balances before considering recovery complete.

## Logs

Application JSON Lines are written to:

```text
EXPENSE_LOG_DIR/app.jsonl
EXPENSE_LOG_DIR/archive/app.YYYY-MM-DD.INDEX.jsonl.gz
```

Rotation limits are:

- 20 MiB per file;
- 30 days of history; and
- 512 MiB total archive size.

Systemd also captures standard error for long-running services. MCP protocol JSON is reserved for standard output and is not mixed with technical log output.

Useful searches:

```bash
rg -i 'error|failed|exception' "$HOME/.local/state/hermes-expense-ledger/logs/app.jsonl"
journalctl --user -u hermes-expense-dashboard.service --since today --no-pager
journalctl --user -u hermes-gateway.service --since today --no-pager
```

Technical log rotation does not remove original financial messages or audit events stored in SQLite.

## Disk and permission checks

```bash
df -h "$HOME"
du -sh "$HOME/backups/hermes-expense-ledger"
du -sh "$HOME/.local/state/hermes-expense-ledger/logs"
stat -c '%a %n' .env
```

Expected `.env` mode is `600`. Backup and data directories should not be writable by unrelated users.

## Maintenance checklist

Monthly:

1. Run `health`.
2. Confirm both services are active.
3. Verify the latest `.sha256` sidecar.
4. Check free disk space.
5. Open the private dashboard from another tailnet device.
6. Review pending drafts and open receivables.
7. Copy at least one verified archive off the server until automated off-site backup exists.
