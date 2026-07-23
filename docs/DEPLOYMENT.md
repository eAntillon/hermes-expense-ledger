# Local Deployment

## Dashboard

Create `.env`, generate a token, and install the user service:

```text
./gradlew installDist
build/install/hermes-expense-ledger/bin/hermes-expense-ledger generate-token
scripts/install-dashboard.sh
```

The dashboard listens on `http://127.0.0.1:8787` by default. The systemd service uses a restrictive umask, restarts on failures, and runs without elevated privileges.

## Tailscale

Tailscale is optional and is not installed by this repository. After installing and authenticating it, keep the Java service on loopback and publish it privately:

```text
tailscale serve --bg http://127.0.0.1:8787
tailscale serve status
```

Review the tailnet access policy before relying on the URL. Never bind the dashboard directly to a public interface.

## Logs

Application logs are JSON Lines in `EXPENSE_LOG_DIR`; the user service also writes to the journal:

```text
journalctl --user -u hermes-expense-dashboard.service
```

Financial originals and audit events remain in SQLite indefinitely. Rotated technical logs retain 30 days or 512 MiB, whichever is reached first.

## Backup limitation

Backups currently remain on the same machine. This protects against database corruption and accidental changes, but not server or disk loss. Copy verified archives off-site manually until a remote backend is implemented.
