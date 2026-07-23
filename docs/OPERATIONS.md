# Operations

## Build

```text
./gradlew --write-locks clean check installDist cyclonedxBom
```

The release workflow uses the committed Gradle wrapper and verifies downloaded artifacts against `gradle/verification-metadata.xml`.

## Database

Run `migrate` before starting services. Migrations are transactional and checksum-protected. The application creates a verified pre-migration backup when an existing database is present.

`health` verifies configuration, database connectivity, migration state, and SQLite integrity. It returns a non-zero exit code on failure.

## Backup

`backup` creates a consistent SQLite snapshot, runs `PRAGMA integrity_check`, compresses the file with GZIP, records a SHA-256 checksum, and applies the configured retention policy. A local backup protects against database corruption and operator mistakes, but not loss of the server. Off-site backup is intentionally deferred.

## Services

The MCP process is launched by Hermes through standard input/output. The dashboard runs as a user systemd service. Both write technical JSONL logs to the configured log directory; MCP protocol messages alone use standard output.

## Private access

Keep the dashboard on loopback. Tailscale Serve can publish the loopback port privately to the owner's tailnet after Tailscale is installed. Do not expose port `8787` directly to the public internet.

## Recovery

1. Stop the dashboard and Hermes MCP process.
2. Verify the selected archive checksum against the corresponding backup record or `.sha256` file.
3. Decompress into a new file; never overwrite the active database first.
4. Run `health` against the restored file.
5. Atomically replace the active database and restart services.
