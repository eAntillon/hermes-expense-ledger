#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="${EXPENSE_ENV_FILE:-${project_dir}/.env}"
skill_source="${project_dir}/skills/manage-expenses"
skill_root="${HERMES_SKILLS_DIR:-${HOME}/.hermes/skills}"
skill_target="${skill_root}/manage-expenses"
mcp_name="hermes-expense-ledger"
replace_mcp=false

if [[ "${1:-}" == "--replace-mcp" ]]; then
    replace_mcp=true
fi
if [[ ! -f "${env_file}" ]]; then
    printf 'Create %s from .env.example before Hermes installation.\n' "${env_file}" >&2
    exit 1
fi

set -a
# shellcheck disable=SC1090
source "${env_file}"
set +a

: "${EXPENSE_DISCORD_CHANNEL_ID:?EXPENSE_DISCORD_CHANNEL_ID is required}"
: "${EXPENSE_DISCORD_ALLOWED_USER_IDS:?EXPENSE_DISCORD_ALLOWED_USER_IDS is required}"
: "${EXPENSE_HERMES_MODEL:=gpt-5.6-luna}"
: "${EXPENSE_HERMES_REASONING_EFFORT:=low}"
: "${EXPENSE_BASE_CURRENCY:=GTQ}"
: "${EXPENSE_TIMEZONE:=America/Guatemala}"
: "${EXPENSE_DB_PATH:=${HOME}/.local/share/hermes-expense-ledger/data/ledger.db}"
: "${EXPENSE_BACKUP_DIR:=${HOME}/backups/hermes-expense-ledger}"
: "${EXPENSE_LOG_DIR:=${HOME}/.local/state/hermes-expense-ledger/logs}"

if [[ "${EXPENSE_DISCORD_CHANNEL_ID}" == "000000000000000000" ]]; then
    printf 'Replace the placeholder Discord channel ID in .env.\n' >&2
    exit 1
fi
if [[ "${EXPENSE_DISCORD_ALLOWED_USER_IDS}" == "000000000000000000" ]]; then
    printf 'Replace the placeholder Discord user ID in .env.\n' >&2
    exit 1
fi

java_bin="$(command -v java || true)"
if [[ -z "${java_bin}" ]]; then
    printf 'Java is not available in PATH. Install Java 22 before installation.\n' >&2
    exit 1
fi
java_home="$(dirname -- "$(dirname -- "$(readlink -f -- "${java_bin}")")")"

"${project_dir}/gradlew" clean check installDist

mkdir -p "${skill_root}"
if [[ -e "${skill_target}" && ! -L "${skill_target}" ]]; then
    printf 'Refusing to replace existing non-symlink skill: %s\n' "${skill_target}" >&2
    exit 1
fi
ln -sfn "${skill_source}" "${skill_target}"

hermes config set model.default "${EXPENSE_HERMES_MODEL}"
hermes config set --force agent.reasoning_effort "${EXPENSE_HERMES_REASONING_EFFORT}"
"${project_dir}/scripts/configure-hermes.sh"

if [[ "${replace_mcp}" == true ]]; then
    printf '\n' | hermes mcp remove "${mcp_name}" || true
fi

launcher="${project_dir}/build/install/hermes-expense-ledger/bin/hermes-expense-ledger"
printf 'y\n\n' | hermes mcp add "${mcp_name}" \
    --command "${launcher}" \
    --connect-timeout 20 \
    --env \
        "EXPENSE_BASE_CURRENCY=${EXPENSE_BASE_CURRENCY}" \
        "EXPENSE_TIMEZONE=${EXPENSE_TIMEZONE}" \
        "EXPENSE_DISCORD_CHANNEL_ID=${EXPENSE_DISCORD_CHANNEL_ID}" \
        "EXPENSE_DB_PATH=${EXPENSE_DB_PATH}" \
        "EXPENSE_BACKUP_DIR=${EXPENSE_BACKUP_DIR}" \
        "EXPENSE_LOG_DIR=${EXPENSE_LOG_DIR}" \
        "JAVA_HOME=${java_home}" \
    --args mcp

if ! configured_launcher="$(hermes config get "mcp_servers.${mcp_name}.command" 2>/dev/null)" \
        || [[ "${configured_launcher}" != "${launcher}" ]]; then
    printf 'Hermes did not persist the expected MCP command.\n' >&2
    exit 1
fi

hermes mcp test "${mcp_name}"

hermes_env_file="$(hermes config env-path)"
if ! grep -q '^DISCORD_ALLOWED_USERS=' "${hermes_env_file}"; then
    printf '\nHermes user allowlist is not set. Add this line to %s:\n' "${hermes_env_file}"
    printf 'DISCORD_ALLOWED_USERS=%s\n' "${EXPENSE_DISCORD_ALLOWED_USER_IDS}"
    printf 'Then restart the gateway with: hermes gateway restart\n'
else
    hermes gateway restart
fi

printf '\nHermes Expense Ledger installed from: %s\n' "${project_dir}"
