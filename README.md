# Hermes Expense Ledger

A private-by-default personal expense ledger for Hermes Agent. It accepts natural-language Discord messages, validates model output in Java, requires preview and confirmation, stores an immutable audit trail in SQLite, and provides a local web dashboard.

## Status

The initial implementation is complete and tested locally. Live Hermes activation, Tailscale installation, and public GitHub publication remain operator-controlled because they require Discord IDs or external credentials.

## Design guarantees

- The model cannot write directly to the ledger: it can only create a pending draft.
- Java validates amounts, currencies, dates, relationships, source channel, and state transitions.
- SQLite uses WAL mode, foreign keys, transactions, and append-only audit events.
- Original Discord text is retained in drafts, ledger entries, and the audit trail.
- Totals are grouped by currency; no implicit foreign-exchange conversion occurs.
- The dashboard binds to loopback by default and requires an access token.
- Backups are local, integrity-checked, compressed, checksummed, and retention-managed.

## Runtime

- Java: Eclipse Temurin 22.0.2
- Build: Gradle 9.6.1 wrapper
- MCP Java SDK: 2.0.0
- SQLite JDBC: 3.53.2.1

All direct and transitive dependency versions are committed through Gradle dependency locks. Artifact checksums are committed through Gradle dependency verification metadata.

## Commands

```text
./gradlew clean check
./gradlew installDist
build/install/hermes-expense-ledger/bin/hermes-expense-ledger migrate
build/install/hermes-expense-ledger/bin/hermes-expense-ledger mcp
build/install/hermes-expense-ledger/bin/hermes-expense-ledger serve
build/install/hermes-expense-ledger/bin/hermes-expense-ledger backup
build/install/hermes-expense-ledger/bin/hermes-expense-ledger health
```

Copy `.env.example` to `.env`, fill the Discord IDs and web access token, then follow [Configuration](docs/CONFIGURATION.md) and [Operations](docs/OPERATIONS.md).

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Configuration](docs/CONFIGURATION.md)
- [Operations](docs/OPERATIONS.md)
- [Security](docs/SECURITY.md)
- [Hermes integration](docs/HERMES.md)
- [Local deployment](docs/DEPLOYMENT.md)
