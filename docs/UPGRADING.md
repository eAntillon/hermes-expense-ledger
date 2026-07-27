# Upgrading

This document owns application updates, rollback, dependency maintenance, and changes to external runtimes. For configuration values, use [Configuration](CONFIGURATION.md). For database restoration, use [Operations and recovery](OPERATIONS.md).

## Normal application update

### 1. Establish a healthy baseline

```bash
cd /home/ubuntu/hermes-expense-ledger

./scripts/run-with-env.sh health
systemctl --user is-active hermes-expense-dashboard.service
hermes gateway status
git status --short --branch
```

Do not pull when tracked files contain unexplained local changes. `.env` is ignored and should not appear in Git status.

### 2. Create a recovery point

```bash
./scripts/run-with-env.sh backup
git rev-parse HEAD
```

Record the current commit ID and retain the successful backup path printed by the command.

### 3. Fetch only a fast-forward update

```bash
git pull --ff-only
```

`--ff-only` prevents an accidental merge commit on the server. Resolve local development work separately instead of merging during deployment.

### 4. Rebuild and reinstall both long-running integrations

```bash
./scripts/install-dashboard.sh
./scripts/install-hermes.sh --replace-mcp
```

Each installer runs the Java tests and rebuilds the distribution before replacing its runtime configuration. Running both is intentionally conservative: it refreshes the systemd Java home, Hermes MCP environment, skill binding, and application artifacts.

### 5. Verify the updated deployment

```bash
./scripts/run-with-env.sh health
systemctl --user is-active hermes-expense-dashboard.service
curl --fail http://127.0.0.1:8787/healthz
hermes mcp test hermes-expense-ledger
hermes gateway status
tailscale serve status
git status --short --branch
```

Also open the dashboard and send a real low-value test movement through Discord, reviewing the draft before confirmation.

## Database migration behavior

Every persistent Java command runs migrations before its requested mode. Migration files are ordered, checksum-protected, and transactional.

The migration runner does not create a backup. The manual backup required before `git pull` is the rollback point for schema changes. Never edit an already-applied migration file; add a new ordered migration instead.

An application rollback does not automatically reverse schema migrations. If an older build cannot use the migrated schema, restore the pre-upgrade database backup using [Operations and recovery](OPERATIONS.md#restore-a-backup).

## Application rollback

Use rollback only after collecting the current logs and preserving both the current database and the known-good backup.

### 1. Stop services and identify commits

```bash
systemctl --user stop hermes-expense-dashboard.service
hermes gateway stop
git log --oneline -10
```

### 2. Select the known-good code without moving the main branch

```bash
git switch --detach KNOWN_GOOD_COMMIT
```

### 3. Reinstall and test

```bash
./scripts/install-dashboard.sh
./scripts/install-hermes.sh --replace-mcp
./scripts/run-with-env.sh health
```

If health fails because the database schema moved forward, stop services and restore the pre-upgrade backup. Do not force an old application to operate on an unsupported schema.

### 4. Return to the tracked branch after the incident

When a corrected commit is available:

```bash
git switch main
git pull --ff-only
./scripts/install-dashboard.sh
./scripts/install-hermes.sh --replace-mcp
```

## Configuration-only update

Do not pull or change code for an environment-only update.

1. Create a manual backup for persistent-path, currency, or timezone changes.
2. Edit `.env` without printing it.
3. Use the [change-impact matrix](CONFIGURATION.md#change-impact-matrix).
4. Verify health and affected integrations.

Because `.env` is outside Git, configuration rollback is manual. Before a risky change, keep a permission-restricted copy outside the repository:

```bash
env_backup_dir="$HOME/.local/state/hermes-expense-ledger/env-backups"
mkdir -p "${env_backup_dir}"
chmod 700 "${env_backup_dir}"
cp -p .env "${env_backup_dir}/env.$(date -u +%Y%m%dT%H%M%SZ)"
```

Never stage or commit these secret backups.

## Java runtime update

The application baseline is Java 22. After replacing or relocating Java:

```bash
java -version
readlink -f "$(command -v java)"
./scripts/install-dashboard.sh
./scripts/install-hermes.sh --replace-mcp
```

Both installers detect the selected Java executable and persist its Java home. Verify that the gateway's MCP child and dashboard remain active after restarting the server.

## Hermes Agent update

Hermes is an external local runtime and has its own update mechanism. Before updating it:

1. Create an application backup.
2. Preserve permission-restricted copies of the Hermes config and environment outside the repository.
3. Record `hermes --version`, `hermes config env-path`, and `hermes gateway status`.
4. Use the update command documented by the installed Hermes distribution.

After the Hermes update:

```bash
hermes config check
./scripts/install-hermes.sh --replace-mcp
hermes skills list
hermes mcp test hermes-expense-ledger
hermes gateway status
```

Confirm that `channel_skill_bindings` is a structured array and that the expense channel remains in `free_response_channels`.

## Model update

Model selection is configuration, not an application dependency. Follow [Model and reasoning changes](CONFIGURATION.md#model-and-reasoning-changes).

The installer changes Hermes global `model.default` and `agent.reasoning_effort`. If the same Hermes instance serves unrelated channels, review the effect on those sessions before changing them.

## Java dependency update

Dependencies use exact versions, Gradle dependency locking, and checksum verification.

For a deliberate dependency change:

1. Edit the exact version in `build.gradle.kts`.
2. Regenerate locks and checksum metadata from trusted repositories.
3. Review every metadata and transitive dependency change.
4. Run the strict build again without write flags.

```bash
./gradlew --write-locks --write-verification-metadata sha256 \
  clean check installDist cyclonedxBom

./gradlew --dependency-verification=strict \
  clean check installDist cyclonedxBom

git diff -- build.gradle.kts gradle.lockfile gradle/verification-metadata.xml
```

Never approve an unexpected artifact checksum merely to make a build pass. Confirm the dependency coordinate, repository, release notes, and expected transitive graph first.

## Gradle wrapper update

Use the existing wrapper to generate a specific trusted version:

```bash
./gradlew wrapper --gradle-version NEW_VERSION --distribution-type bin
./gradlew wrapper
./gradlew --dependency-verification=strict clean check installDist cyclonedxBom
```

Review `gradle/wrapper/gradle-wrapper.properties`, the wrapper JAR change, locks, and verification metadata before committing.

## Skill-only update

The installed Hermes skill is a symlink to the tracked `skills/manage-expenses` directory. A Git update changes the skill content in place, but existing Hermes sessions may retain earlier instructions.

After a skill-only change:

```bash
hermes gateway restart
hermes skills list
```

Start a new Discord conversation or session before evaluating the new behavior.

## Documentation-only update

Documentation changes do not require service restarts. Validate Markdown links and run the repository checks appropriate to the changed examples before pushing.

## Release verification checklist

```bash
./gradlew --write-locks --dependency-verification=strict \
  clean check installDist cyclonedxBom
bash -n scripts/*.sh
git diff --check
git status --short --branch
```

Before pushing, confirm that `.env`, SQLite files, backups, logs, and real financial examples are not staged.
