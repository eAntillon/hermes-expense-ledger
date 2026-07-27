# Troubleshooting

Use this guide by symptom. Begin with non-mutating checks and avoid printing credentials or real financial payloads.

## Baseline diagnostic bundle

```bash
cd /home/ubuntu/hermes-expense-ledger

git status --short --branch
java -version
./scripts/run-with-env.sh health
systemctl --user status hermes-expense-dashboard.service --no-pager
hermes gateway status
hermes mcp test hermes-expense-ledger
tailscale serve status
```

Then inspect only the relevant recent journal:

```bash
journalctl --user -u hermes-expense-dashboard.service -n 100 --no-pager
journalctl --user -u hermes-gateway.service -n 150 --no-pager
```

## `Missing application distribution`

Cause: `build/install/hermes-expense-ledger` does not exist, often after a fresh clone or interrupted clean build.

Fix:

```bash
./gradlew --dependency-verification=strict clean check installDist
```

Then rerun the appropriate installer.

## Dashboard service reports `218/CAPABILITIES`

Cause: an older rendered systemd unit contains hardening directives unsupported by the host's user service manager.

Fix from the latest project revision:

```bash
./scripts/install-dashboard.sh
systemctl --user daemon-reload
systemctl --user restart hermes-expense-dashboard.service
```

Do not keep editing the rendered unit; the tracked template is the source of truth.

## Dashboard reports `JAVA_HOME is not set`

Cause: the systemd user environment cannot see an interactive-shell Java installation, or the Java installation moved.

Fix:

```bash
command -v java
readlink -f "$(command -v java)"
java -version
./scripts/install-dashboard.sh
```

The installer records the detected Java home in the rendered unit.

## Hermes reports MCP `Connection closed` or `parked`

Likely causes:

- missing Java home in the MCP entry;
- missing or incomplete application distribution;
- invalid MCP environment path;
- SQLite startup failure; or
- stale Hermes MCP configuration.

Fix:

```bash
./gradlew --dependency-verification=strict clean check installDist
./scripts/install-hermes.sh --replace-mcp
hermes mcp test hermes-expense-ledger
hermes gateway restart
```

Confirm the gateway process tree contains Java:

```bash
pgrep -af 'dev.eantillon.expenseledger.Main mcp'
```

Inspect focused journal lines:

```bash
journalctl --user -u hermes-gateway.service -n 200 --no-pager \
  | rg -i 'mcp|java|error|failed|parked|connection closed'
```

## Hermes reports `No MCP servers configured`

Cause: the MCP registration was removed or an older interactive installer cancelled before saving it.

Fix:

```bash
./scripts/install-hermes.sh --replace-mcp
hermes mcp test hermes-expense-ledger
```

The current installer supplies non-interactive confirmation and verifies the persisted command.

## Bot does not answer in the expense channel

Check in order:

1. The gateway is active: `hermes gateway status`.
2. The intended account is present in `DISCORD_ALLOWED_USERS`.
3. `.env` contains the correct `EXPENSE_DISCORD_CHANNEL_ID`.
4. The skill is enabled: `hermes skills list`.
5. The binding is structured: `hermes config get platforms.discord.channel_skill_bindings --json`.
6. The channel is free-response: `hermes config get platforms.discord.free_response_channels --json`.
7. The MCP test discovers ten tools.

Repair all managed Hermes settings with:

```bash
./scripts/install-hermes.sh --replace-mcp
```

## Bot answers only when mentioned

Cause: the expense channel is missing from `platforms.discord.free_response_channels`.

Fix:

```bash
./scripts/configure-hermes.sh
hermes gateway restart
hermes config get platforms.discord.free_response_channels --json
```

The Java configurator preserves other channel values.

## Draft creation says writes are allowed only from another channel

Cause: the Hermes session channel and Java `EXPENSE_DISCORD_CHANNEL_ID` disagree, often after changing `.env` without replacing the MCP entry.

Fix:

```bash
./scripts/install-hermes.sh --replace-mcp
hermes gateway restart
```

Test with a new message in the newly configured dedicated channel.

## Duplicate source-message error

Cause: the same Discord message ID already created a draft. This is the intended idempotency boundary.

Do not try to create another movement from that message. Inspect pending drafts or the dashboard, then confirm, edit, or cancel the existing draft. Send a new Discord message for a separate movement.

## Loan payment requires `related_entry_id`

Cause: repayments must be linked to one confirmed loan.

Ask Hermes to show open receivables, select the intended loan entry ID, and send a new repayment message referencing it. If a repayment exceeds the balance or uses another currency, correct the amount or currency before confirmation.

## Local dashboard is unavailable

```bash
systemctl --user is-active hermes-expense-dashboard.service
curl --verbose http://127.0.0.1:8787/healthz
ss -ltnp 'sport = :8787'
journalctl --user -u hermes-expense-dashboard.service -n 100 --no-pager
```

Common causes are an invalid dashboard token, port conflict, missing Java distribution, invalid path, or failed SQLite startup. Correct `.env` and rerun `./scripts/install-dashboard.sh`.

## Dashboard login always fails

Cause: the entered token differs from `EXPENSE_WEB_ACCESS_TOKEN`, the placeholder was never replaced, or the service was not restarted after rotation.

Generate and install a new token following [Configuration](CONFIGURATION.md#rotate-the-dashboard-token). Do not print the current `.env` to compare it in support output.

## Tailscale says `Serve is not enabled on your tailnet`

Open the owner-approval URL printed by Tailscale, enable Serve, and rerun:

```bash
tailscale serve --bg http://127.0.0.1:8787
```

Do not enable Funnel.

## Tailscale says `serve config denied`

Give the current account local operator permission once:

```bash
sudo tailscale set --operator="$USER"
tailscale serve --bg http://127.0.0.1:8787
```

Verify that `tailscale serve status` says `tailnet only`.

## Tailscale URL opens but returns a gateway error

Tailscale is working, but the loopback service or target port is wrong.

```bash
curl --fail http://127.0.0.1:8787/healthz
tailscale serve status
```

If `EXPENSE_WEB_PORT` changed, reapply Serve with the same new port.

## Health reports migration mismatch

Cause: an applied migration SQL file was edited after use, or the running distribution and database do not belong to the same application revision.

Do not alter the checksum stored in SQLite. Restore the expected source revision or a verified compatible backup. Applied migration files must remain immutable; future schema work requires a new migration.

## SQLite is locked

Short overlap is handled by a five-second busy timeout. Persistent locking can indicate a hung process or an external SQLite client.

```bash
pgrep -af 'dev.eantillon.expenseledger.Main'
fuser "$HOME/.local/share/hermes-expense-ledger/data/ledger.db" || true
```

Stop external SQLite clients. Restart the dashboard and Hermes gateway only after confirming no maintenance or backup is still running.

## SQLite or logs are read-only

Check the configured paths, owner, permissions, and disk state:

```bash
./scripts/run-with-env.sh health
df -h "$HOME"
namei -l "$HOME/.local/share/hermes-expense-ledger/data/ledger.db"
namei -l "$HOME/.local/state/hermes-expense-ledger/logs"
```

Do not solve permission errors with world-writable modes. The service should run as the same Linux owner that owns the repository and data directories.

## Backup fails

Check application health, free space, path permissions, and recent logs:

```bash
./scripts/run-with-env.sh health
df -h "$HOME"
ls -ld "$HOME/backups/hermes-expense-ledger"
rg -i 'backup|error|failed' "$HOME/.local/state/hermes-expense-ledger/logs/app.jsonl"
```

After correcting the cause, run one manual backup and verify its `.sha256` sidecar.

## `git pull` cannot authenticate

The public repository can be fetched without a token. Confirm the remote:

```bash
git remote -v
git ls-remote https://github.com/eAntillon/hermes-expense-ledger.git HEAD
```

Pushing requires authenticated GitHub access, but normal server updates should only need `git pull --ff-only`. Never embed a token in the remote URL.

## Build fails dependency verification

Do not disable strict verification. Inspect the exact dependency and checksum change. If the dependency update is intentional, follow [Java dependency update](UPGRADING.md#java-dependency-update). An unexplained checksum failure is a supply-chain warning, not a cache inconvenience.

## Warnings that do not affect expense text messages

These Hermes warnings are unrelated to the current text-only channel unless media or voice features are added:

- missing Opus codec for voice playback;
- missing host-visible media output mount.

Do not ignore MCP, Discord authentication, connection, database, or channel-binding errors.

## Escalation data

When a problem remains, collect only:

- current Git commit;
- Java and Hermes versions;
- service active states;
- sanitized health output;
- the failing command and exit code; and
- a minimal redacted journal excerpt.

Never include `.env`, Hermes environment contents, tokens, real financial messages, complete MCP payloads, or backup archives.
