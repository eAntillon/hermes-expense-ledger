# Security

This document defines the supported threat model, required controls, retention policy, and incident response for the one-owner deployment.

## Supported deployment

The project is designed for:

- one owner or a very small explicit Discord allowlist;
- a dedicated expense channel;
- a trusted Linux user account;
- a dashboard bound only to loopback;
- private remote access through Tailscale Serve; and
- a public source repository containing no runtime data or secrets.

It is not designed as a public multi-tenant finance service, an Internet-facing accounting API, or a replacement for regulated accounting software.

## Protected assets

- Discord bot and provider credentials.
- Dashboard access token and sessions.
- Original financial messages.
- Draft and confirmed ledger data.
- Loan and receivable relationships.
- Append-only audit history.
- Database and backup archives.
- Technical logs that may reveal operational metadata.

## Primary threats and controls

| Threat | Control |
| --- | --- |
| Model invents or misinterprets a movement | Strict tool schema, Java domain validation, canonical preview, separate confirmation turn |
| Discord event replay | Unique channel/message idempotency key |
| Message from another channel | Hermes skill binding plus independent Java channel check |
| Unauthorized Discord user | Hermes `DISCORD_ALLOWED_USERS` gateway boundary |
| Public dashboard exposure | Mandatory loopback bind and Tailscale Serve |
| Dashboard request forgery | Server-side session and per-session CSRF token |
| Token timing attack | SHA-256 plus constant-time comparison |
| Browser data leakage | CSP, no-store cache, no-referrer, no framing, no external resources |
| Partial financial write | SQLite transaction and constraints |
| Audit tampering through normal SQL | Append-only update/delete triggers |
| Corrupt backup | Snapshot integrity check, GZIP test capability, SHA-256 sidecar |
| Dependency substitution | Exact versions, dependency locks, artifact checksum verification |

## Secret locations

| Secret | Supported location |
| --- | --- |
| Dashboard token | Project `.env`, mode `600` |
| Discord bot token | Hermes environment returned by `hermes config env-path` |
| Provider/Codex credential | Hermes credential store or protected Hermes environment |
| GitHub token | Protected credential store or environment, never remote URL |

The project `.env`, Hermes environment, databases, backups, and logs must remain outside Git.

Safe permission checks:

```bash
stat -c '%a %n' .env
stat -c '%a %n' "$HOME/.hermes/config.yaml"
stat -c '%a %n' "$(hermes config env-path)"
```

Do not use commands that print complete environment files during support or verification.

## Dashboard token and session security

Generate dashboard tokens with the Java `generate-token` command. Tokens contain 256 bits of randomness and must be at least 32 characters.

The application loads the configured token at startup, and the authentication helper retains a SHA-256 value for constant-time login comparison. Successful login creates independent 256-bit session and CSRF values.

Session properties:

- stored only in server memory;
- 12-hour lifetime;
- `HttpOnly` cookie;
- `SameSite=Strict` cookie;
- no browser persistence beyond `Max-Age`; and
- invalidated by logout or dashboard restart.

The current cookie does not carry the `Secure` attribute because the Java backend receives loopback HTTP. The supported browser route is still HTTPS through Tailscale Serve, and no tailnet HTTP route should be added. Do not expose the loopback backend through another proxy or public listener.

Rotate the token immediately after suspected disclosure using [Configuration](CONFIGURATION.md#rotate-the-dashboard-token).

## Network boundary

`EXPENSE_WEB_BIND` must resolve to a loopback address. The application exits if configured with a non-loopback bind.

Supported:

```text
Browser -> private tailnet HTTPS -> Tailscale Serve -> 127.0.0.1:8787
```

Unsupported:

- binding the dashboard to `0.0.0.0`;
- opening port 8787 in the cloud firewall;
- using Tailscale Funnel;
- publishing the dashboard through an unauthenticated public reverse proxy; or
- sharing the tailnet URL and token as if URL secrecy were an access control.

Review tailnet membership and ACLs periodically. Remove lost or unused devices from the Tailscale administration console.

## Discord boundary

The Discord channel should contain only financial interactions with this skill. Keep bot permissions minimal: access to the intended server and channel, message reading, and response permissions required by the working Hermes adapter.

`DISCORD_ALLOWED_USERS` is the live user authorization boundary. The project `.env` copy alone does not enforce access; keep both synchronized as described in [Configuration](CONFIGURATION.md#discord-ids-and-allowlists).

Changing the channel or allowlist requires a Hermes reinstall and gateway verification.

## Model and prompt safety

The original Discord message is untrusted data. The skill tells Hermes not to execute instructions found inside the financial text and not to invent missing amount, currency, person, date, or relationship information.

The model can create a pending draft, but confirmation is a different tool call after user review. Java and SQLite remain authoritative even when the model violates its instructions.

Never grant the model raw SQLite access, unrestricted shell access for financial writes, or the dashboard token.

## Data retention

Original messages, drafts, confirmed entries, and audit events are retained indefinitely in SQLite. This is intentional for traceability.

Technical JSONL logs retain up to 30 days and 512 MiB. They should contain operational messages, not access tokens or full environment values.

Local backups use configured rolling retention. Deleting a retained backup does not delete its corresponding financial database record, but expiration removes the archive and checksum sidecar.

When permanent deletion becomes necessary, define and implement a separate audited policy. Manual database deletion is outside the supported workflow.

## Backup security

Backups contain the full financial database and are as sensitive as the active database.

- Keep backup directories private to the Linux owner.
- Copy only checksum-verified archives.
- Use an encrypted destination for any manual off-site copy.
- Do not upload archives to public issue trackers, generic file-sharing links, or untrusted cloud accounts.
- Preserve `.sha256` sidecars with archives.

The current implementation does not encrypt archives itself and does not provide automated off-site backup.

## Known security limits

- SQLite, backups, and technical logs are not encrypted by the application. Host and destination encryption are operator responsibilities.
- Dashboard login has no application-level rate limiter. The high-entropy token and private tailnet boundary are required controls.
- Any process running as the same trusted Linux user can read the application files and environment.
- The dashboard session cookie depends on the supported Tailscale HTTPS route and does not have a backend-set `Secure` attribute.
- Confirmed-entry correction and audited deletion workflows are not implemented.
- Automated off-site backup and archive encryption are not implemented.

## Supply-chain controls

- The Gradle wrapper version is committed.
- Direct dependency versions are exact.
- Transitive resolution is locked in `gradle.lockfile`.
- Artifact checksums are committed in `gradle/verification-metadata.xml`.
- Strict verification is used for release-grade builds.
- A CycloneDX bill of materials can be generated with `cyclonedxBom`.

Review dependency metadata changes instead of accepting new checksums automatically.

## Logging and support hygiene

Before sharing diagnostic output:

1. remove Discord IDs when they are not needed;
2. remove financial text and UUIDs;
3. never include `.env` or Hermes environment contents;
4. avoid complete MCP payloads containing original messages; and
5. share only the smallest journal excerpt that demonstrates the failure.

## Incident response

### Suspected dashboard token disclosure

1. Stop Tailscale Serve or the dashboard service.
2. Generate and install a new dashboard token.
3. Restart the dashboard to invalidate sessions.
4. Review dashboard and Tailscale access logs available to the deployment.

### Suspected Discord bot token disclosure

1. Stop the Hermes gateway.
2. Rotate the token in Discord's developer portal.
3. update the protected Hermes environment;
4. restart and test the gateway; and
5. review recent ledger drafts and audit events.

### Suspected financial data tampering

1. Stop the dashboard and Hermes gateway.
2. preserve the active database, WAL/SHM sidecars, backups, and logs without modifying them;
3. calculate checksums of preserved files;
4. inspect append-only audit events and verified backups; and
5. restore only after identifying the last trusted archive.

### Public repository exposure

If a credential or financial file is committed, deletion from the latest commit is insufficient. Rotate every exposed credential immediately, remove the data from Git history, and treat existing clones and caches as compromised.

## Security reporting

Do not open a public issue containing real financial data, Discord IDs, server addresses, credentials, or backup contents. Report only sanitized reproduction details and the affected commit or version.
