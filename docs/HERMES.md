# Hermes Integration

## Prerequisites

- Hermes Agent 0.19.0 or newer is running locally.
- The Discord bot and gateway already work.
- `.env` contains the dedicated Discord channel ID and allowed owner ID.
- `DISCORD_ALLOWED_USERS` in the Hermes environment includes the same owner ID.

## Installation

```text
cp .env.example .env
# Edit .env without committing it.
scripts/install-hermes.sh
```

The installer performs four scoped changes:

1. Builds and tests the Java distribution.
2. Symlinks `manage-expenses` into `~/.hermes/skills`, preserving Git as the source of truth.
3. Binds that skill to the configured Discord channel and selects the configured Codex model and reasoning effort.
4. Registers the Java process as a STDIO MCP server and runs Hermes tool discovery.

Use `scripts/install-hermes.sh --replace-mcp` only when replacing an existing MCP entry with the same name.

## Discord behavior

The skill requires a draft preview and a later explicit confirmation. Hermes supplies the channel and triggering message IDs; Java rejects any write from another channel. The owner allowlist remains enforced by the existing Hermes gateway.

If the bot should respond without a mention in the expense channel, add the channel ID to `platforms.discord.free_response_channels` through the existing Hermes configuration. Preserve any channels already present.

## Verification

```text
hermes skills list
hermes mcp test hermes-expense-ledger
hermes config get platforms.discord.channel_skill_bindings --json
hermes config get model.default --json
hermes config get agent.reasoning_effort --json
hermes gateway status
```

Do not paste access tokens or the full Hermes environment into issues or chat.
