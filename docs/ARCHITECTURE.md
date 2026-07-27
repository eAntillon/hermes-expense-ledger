# Architecture

This document describes internal design and invariants. User procedures live in [Daily usage](USAGE.md); operational procedures live in [Operations and recovery](OPERATIONS.md).

## Design goals

- Keep deployment small: one Java distribution and one SQLite file.
- Treat language-model output as untrusted structured input.
- Require human review before a proposed movement becomes a confirmed entry.
- Preserve source text and an append-only audit history.
- Avoid a public dashboard listener and a separate database server.
- Keep all monetary operations exact and currency-aware.

## Runtime modes

One Java entry point exposes independent commands:

| Command | Lifetime | Responsibility |
| --- | --- | --- |
| `mcp` | Long-running under Hermes | Ten STDIO MCP tools |
| `serve` | Long-running under systemd | Authenticated dashboard, health endpoint, daily backup scheduler |
| `migrate` | One-shot | Apply checksum-protected schema migrations |
| `backup` | One-shot | Produce and verify a compressed local snapshot |
| `health` | One-shot | Check configuration, migration state, SQLite integrity, and backup state |
| `generate-token` | One-shot | Generate a 256-bit dashboard secret without loading application configuration |

The MCP and dashboard processes use the same database and service layer. No financial behavior exists only in the web UI or only in Discord.

## Process topology

```text
Discord
  |
  v
Hermes gateway
  |- model + manage-expenses skill
  `- Java MCP child process ------------------.
                                                |
                                                v
Browser -> Tailscale Serve -> Java dashboard -> service layer -> SQLite
                                                |
                                                `-> local backup archives
```

Hermes supervises the MCP child over STDIO. systemd supervises the dashboard. Tailscale Serve proxies private HTTPS to the dashboard's loopback HTTP listener.

## Trust boundaries

### Discord and Hermes

Hermes authenticates the bot, enforces `DISCORD_ALLOWED_USERS`, supplies channel and message context, selects the channel-bound skill, and calls MCP tools.

### Model output

Model output is a proposal. The model cannot issue SQL and cannot bypass Java tool handlers. Instructions embedded in an expense message are treated as untrusted source text.

### Java validation

MCP JSON Schema checks shape and types. Java then validates semantic rules, including channel identity, positive amount, currency, date, required loan relationships, source IDs, string lengths, and allowed state transitions.

### SQLite

SQLite provides uniqueness, foreign keys, strict tables, positive amount checks, enum checks, transactions, and append-only audit triggers.

### Dashboard

The dashboard accepts only a generated access token, creates server-side sessions, validates CSRF tokens on mutations, and refuses non-loopback bind addresses.

## Write lifecycle

```text
new message
   |
   v
PENDING draft --edit--> PENDING draft (new version)
   |                         |
   | confirm                 | cancel
   v                         v
CONFIRMED draft          CANCELLED draft
   |
   `-> one ACTIVE ledger entry + audit event
```

Detailed sequence:

1. Hermes loads `manage-expenses` for the configured channel.
2. The model extracts one proposed movement from one Discord message.
3. `expense_draft_create` receives the raw text and Discord source IDs.
4. MCP schema and Java domain validation run.
5. The repository stores a `PENDING` draft and append-only audit event.
6. Java returns a canonical preview.
7. A later user action edits, confirms, or cancels the draft.
8. Confirmation creates one ledger entry and updates the draft in one transaction.

The pair `(source_channel_id, source_message_id)` is unique. Discord retries or repeated tool calls for the same source message cannot create duplicate drafts.

## Money representation

Input amounts are decimal strings parsed with `BigDecimal`. Currency metadata comes from `java.util.Currency`.

After validation, amounts are stored as positive integer minor units. For GTQ or USD, `140.10` becomes `14010`. Binary floating point is never used for financial values.

Currency determines fractional precision. The movement type determines accounting direction; negative input amounts are rejected.

No exchange-rate subsystem exists. Reporting groups each currency independently.

## Financial semantics

| Type | Relationship behavior |
| --- | --- |
| Expense | Independent confirmed spending entry |
| Refund | May reference an expense; linked refunds match currency and cannot exceed the expense balance |
| Loan | Requires a person and creates a receivable |
| Loan payment | Must reference a confirmed loan, match its currency, and not exceed its remaining balance |

Confirmed entries use `ACTIVE` status in the current interfaces. The schema reserves `VOID` for a future audited correction workflow, but no current MCP or dashboard action voids an entry.

## Persistence model

| Table | Purpose |
| --- | --- |
| `schema_migrations` | Applied version and SQL checksum |
| `drafts` | Proposed movement, original text, source IDs, version, and review state |
| `ledger_entries` | Confirmed financial records and optional relationship |
| `audit_events` | Append-only JSON snapshots of state changes and actors |
| `backup_runs` | Backup status, archive path, SHA-256, size, and timestamps |

Audit database triggers reject update and delete operations on `audit_events`. Application actors distinguish Hermes and dashboard actions.

## Transactions and concurrency

Every connection enables:

```text
PRAGMA foreign_keys = ON
PRAGMA busy_timeout = 5000
PRAGMA journal_mode = WAL
PRAGMA synchronous = NORMAL
```

WAL mode permits concurrent readers and one writer. A five-second busy timeout handles short overlap between dashboard and MCP writes. Draft confirmation and its related audit write are transactional.

Draft versions provide optimistic concurrency for edits. A stale dashboard edit cannot silently overwrite a newer draft version.

## Migrations

Migration SQL is embedded in the Java artifact. Each migration has a code-computed SHA-256 stored in `schema_migrations`.

Migrations execute in one transaction. Startup fails if an already-applied version's checksum differs from the embedded SQL. Operators must create a verified backup before application updates because migration rollback is not automatic.

## Backup design

Backups use SQLite `VACUUM INTO` to create a consistent standalone database. The snapshot receives an integrity check before GZIP compression and atomic publication.

Each archive has a `.sha256` sidecar and a `backup_runs` record. Retention preserves a rolling newest set plus monthly representatives.

The dashboard owns the daily scheduler; manual backup and MCP backup tools use the same service.

## Dashboard design

The dashboard uses the JDK HTTP server and server-rendered HTML. It has no client-side framework or external asset dependency.

- `/healthz` is unauthenticated and returns only `ok` or `unhealthy`.
- `/login` accepts the dashboard token.
- All other pages require an in-memory server-side session.
- Mutating draft and logout forms require CSRF validation.
- Security headers disable external content, framing, caching, and referrer leakage.
- Sessions last 12 hours and disappear when the process restarts.

The home view intentionally limits pending drafts and recent entries to 50 records each.

## Logging boundary

Technical application events go to standard error and JSONL files. STDIO MCP protocol traffic exclusively uses standard output.

Original Discord messages are business records in SQLite, not technical logs. Technical rotation therefore cannot remove the retained source record or audit history.

## Failure behavior

- Invalid model output returns a validation error and performs no confirmed write.
- A failed confirmation transaction leaves no partial ledger entry.
- A duplicate Discord source message cannot create a duplicate draft.
- A migration checksum mismatch stops startup.
- A failed backup is recorded and partial archives are removed.
- A dashboard bind outside loopback stops startup.
- An unavailable MCP child leaves Discord read/write tools unavailable but does not corrupt SQLite.

## Current scope

- One-owner deployment.
- Expenses, refunds, loans, and loan payments.
- Local SQLite and local backups.
- Tailnet-only dashboard access.
- No exchange-rate conversion.
- No confirmed-entry correction interface yet.
- No automated cloud or off-site backup backend yet.
