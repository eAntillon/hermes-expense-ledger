# Documentation Index

This directory is the detailed source of truth for operating Hermes Expense Ledger. The repository root [README](../README.md) is the short re-entry guide; the files below own the complete procedures.

## Recommended reading order

1. [Local deployment](DEPLOYMENT.md) — install or reconstruct the application, Hermes integration, dashboard service, and private Tailscale access.
2. [Configuration](CONFIGURATION.md) — every environment variable, validation rule, secret, and the command required after each type of change.
3. [Daily usage](USAGE.md) — Discord phrases, preview and confirmation workflow, loans, refunds, multiple currencies, queries, and dashboard actions.
4. [Operations and recovery](OPERATIONS.md) — health checks, service control, backups, logs, database recovery, and routine maintenance.
5. [Upgrading](UPGRADING.md) — safe Git updates, application rollback, configuration-only changes, dependency updates, and Hermes upgrades.
6. [Hermes integration](HERMES.md) — skill binding, MCP registration, Discord allowlist boundaries, model selection, and integration verification.
7. [Architecture](ARCHITECTURE.md) — processes, trust boundaries, write lifecycle, persistence model, concurrency, and failure behavior.
8. [Security](SECURITY.md) — supported threat model, secrets, network exposure, retention, and incident response.
9. [Troubleshooting](TROUBLESHOOTING.md) — symptom-based diagnostics and recovery commands.

## Topic ownership

To prevent contradictory instructions, each topic has one detailed owner:

| Topic | Canonical document |
| --- | --- |
| First installation and Tailscale Serve | [DEPLOYMENT.md](DEPLOYMENT.md) |
| Environment variables and change impact | [CONFIGURATION.md](CONFIGURATION.md) |
| Financial semantics and user interactions | [USAGE.md](USAGE.md) |
| Services, backups, logs, and restore | [OPERATIONS.md](OPERATIONS.md) |
| Git, releases, rollback, and dependencies | [UPGRADING.md](UPGRADING.md) |
| Hermes, Discord, skill, and MCP behavior | [HERMES.md](HERMES.md) |
| Internal design and invariants | [ARCHITECTURE.md](ARCHITECTURE.md) |
| Security requirements and incident handling | [SECURITY.md](SECURITY.md) |
| Failures and diagnostic decision paths | [TROUBLESHOOTING.md](TROUBLESHOOTING.md) |

When a summary elsewhere differs from a detailed procedure, use the canonical document above and correct the stale summary in the same change.

## Documentation conventions

- Commands assume the repository is `/home/ubuntu/hermes-expense-ledger` unless the command first changes directory.
- Commands beginning with `./scripts/run-with-env.sh` load the ignored project `.env` file.
- Commands use the committed Gradle wrapper; a system Gradle installation is neither required nor supported.
- Real secrets, Discord IDs, financial messages, and backup contents must never be added to documentation examples.
- Tailscale Serve means private tailnet access. Tailscale Funnel is outside the supported deployment.
