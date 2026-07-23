# Security

## Supported deployment

This project is designed for one owner on a private machine. The Discord channel is dedicated to financial entries, Hermes restricts callers, the Java service verifies the channel again, and the dashboard remains private through loopback or Tailscale.

## Data retention

Original financial messages and audit events are retained indefinitely. Technical logs rotate locally and must not contain access tokens or full environment values. Database files, backups, logs, and `.env` are excluded from Git.

## Secrets

Use a generated dashboard token of at least 256 bits. Store Discord, GitHub, Codex, and dashboard credentials only in protected environment files or the existing Hermes credential store. Never place secrets in prompts, repository files, command output, or MCP responses.

## Reporting

Do not open a public issue containing real financial data or credentials. Rotate any exposed credential immediately, preserve relevant local logs, and report only sanitized reproduction details.
