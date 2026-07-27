# Local Deployment

This is the canonical installation procedure for one owner on a Linux server. It reconstructs the Java application, Hermes integration, dashboard service, and private Tailscale access without putting credentials in Git.

For an existing healthy installation that only needs new Git commits, use [Upgrading](UPGRADING.md) instead.

## Target topology

```text
Discord -> Hermes gateway -> Java MCP process -> SQLite
                                      ^
Browser -> Tailscale Serve -> Java dashboard
```

The dashboard listens only on loopback. Tailscale Serve terminates private HTTPS for the tailnet. The Java MCP process is started and supervised by Hermes; the dashboard is a systemd user service.

## Prerequisites

- A Linux host with systemd user services.
- Git and Bash.
- Java 22 available as `java` in the interactive installation shell.
- A working local Hermes Agent installation and Discord gateway.
- A dedicated Discord channel for financial messages.
- The owner's Discord user ID and the dedicated channel ID.
- Tailscale installed and authenticated when private remote dashboard access is desired.

Confirm the core tools:

```bash
java -version
git --version
hermes --version
hermes gateway status
tailscale status
```

## 1. Obtain the repository

For a new checkout:

```bash
cd /home/ubuntu
git clone https://github.com/eAntillon/hermes-expense-ledger.git
cd hermes-expense-ledger
```

For the existing server, the expected repository is:

```text
/home/ubuntu/hermes-expense-ledger
```

Never replace an existing checkout without first preserving its ignored `.env` and checking for uncommitted work.

## 2. Create the local configuration

```bash
cp .env.example .env
chmod 600 .env
```

Edit `.env` and replace at least:

```dotenv
EXPENSE_DISCORD_CHANNEL_ID=your_discord_channel_id
EXPENSE_DISCORD_ALLOWED_USER_IDS=your_discord_user_id
EXPENSE_WEB_ACCESS_TOKEN=replace_after_generation
```

Keep the default locale values for Guatemala unless intentionally changing them:

```dotenv
EXPENSE_BASE_CURRENCY=GTQ
EXPENSE_TIMEZONE=America/Guatemala
```

See [Configuration](CONFIGURATION.md) for the complete reference. Discord IDs must be numeric snowflakes between 15 and 22 digits.

## 3. Build and generate the dashboard token

Use the committed Gradle wrapper:

```bash
./gradlew --dependency-verification=strict clean check installDist
build/install/hermes-expense-ledger/bin/hermes-expense-ledger generate-token
```

Copy the generated value into `EXPENSE_WEB_ACCESS_TOKEN` in `.env`, then confirm permissions without printing its contents:

```bash
chmod 600 .env
stat -c '%a %n' .env
```

The token generator uses 32 random bytes and URL-safe Base64. Do not reuse the Discord, GitHub, or provider token.

## 4. Verify the Hermes Discord boundary

Find the Hermes environment file:

```bash
hermes config env-path
```

That file must already contain the working Discord bot credential. Ensure `DISCORD_ALLOWED_USERS` includes the same user IDs configured in `EXPENSE_DISCORD_ALLOWED_USER_IDS`:

```dotenv
DISCORD_ALLOWED_USERS=your_discord_user_id
```

Keep the Hermes environment file permission-restricted. Do not print the complete file during verification.

## 5. Install the Hermes skill and MCP server

```bash
./scripts/install-hermes.sh --replace-mcp
```

The installer:

1. builds and tests the Java distribution;
2. creates a Git-backed skill symlink under `~/.hermes/skills/manage-expenses`;
3. sets the configured Hermes model and reasoning effort;
4. writes a structured Discord channel-to-skill binding;
5. adds the channel to `free_response_channels` without removing existing values;
6. registers all ten Java MCP tools with an explicit Java home; and
7. tests tool discovery and restarts the gateway when the allowlist exists.

The Java configurator creates `~/.hermes/config.yaml.expense-ledger.bak` before changing Hermes YAML.

Verify:

```bash
hermes skills list
hermes mcp test hermes-expense-ledger
hermes config get platforms.discord.channel_skill_bindings --json
hermes gateway status
```

Expected MCP discovery: ten tools. The configured binding must be a JSON array, not a quoted string containing JSON.

## 6. Install the dashboard user service

```bash
./scripts/install-dashboard.sh
```

The installer detects the current Java home, renders the systemd user unit, builds and tests the application, and enables the service.

Verify local-only access:

```bash
systemctl --user is-active hermes-expense-dashboard.service
curl --fail http://127.0.0.1:8787/healthz
ss -ltnp 'sport = :8787'
```

The expected health response is `ok`. The listener must be `127.0.0.1` or its IPv4-mapped loopback representation, never `0.0.0.0` or a public address.

To make user services survive logout and server restarts, check systemd linger:

```bash
loginctl show-user "$USER" -p Linger
```

If it reports `Linger=no`, enable it once:

```bash
sudo loginctl enable-linger "$USER"
```

## 7. Publish the dashboard privately with Tailscale

Confirm Tailscale is authenticated:

```bash
tailscale status
```

Allow the current Linux account to manage Tailscale Serve without repeated root use:

```bash
sudo tailscale set --operator="$USER"
```

Publish the loopback dashboard:

```bash
tailscale serve --bg http://127.0.0.1:8787
tailscale serve status
```

The first invocation may display an owner-approval URL. Open it, enable Serve for the tailnet, and rerun the command. The final output must describe the HTTPS URL as `tailnet only`.

Do not run `tailscale funnel`. Funnel is public Internet exposure and is outside the supported deployment.

Verify private HTTPS from the server:

```bash
dashboard_url="$(tailscale serve status | sed -n 's/ .*//p' | head -1)"
curl --fail "${dashboard_url}/healthz"
```

Alternatively, copy the URL shown by `tailscale serve status` and open it from a phone or computer connected to the same tailnet.

## 8. Create the first backup

```bash
./scripts/run-with-env.sh backup
./scripts/run-with-env.sh health
```

The backup command returns a path, SHA-256 checksum, and byte size. Health should then report the most recent backup as `succeeded`.

## 9. Final acceptance check

```bash
./scripts/run-with-env.sh health
systemctl --user is-active hermes-expense-dashboard.service
hermes gateway status
hermes mcp test hermes-expense-ledger
tailscale serve status
git status --short --branch
```

Then send a real, small expense in the configured Discord channel. Confirm that Hermes shows a preview and does not record the movement until a second explicit confirmation. Use the dashboard to verify the confirmed entry and original message.

## Reinstallation behavior

Both installation scripts are safe to rerun. They rebuild and retest before replacing runtime configuration.

- Use `./scripts/install-dashboard.sh` after dashboard or Java runtime changes.
- Use `./scripts/install-hermes.sh --replace-mcp` after model, Discord channel, MCP, or Java runtime changes.
- Run both when shared application paths, currency, timezone, database, backup, or logging settings change.

Reinstallation does not delete the SQLite database or `.env`.
