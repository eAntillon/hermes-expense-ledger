#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="${EXPENSE_ENV_FILE:-${project_dir}/.env}"
unit_template="${project_dir}/deployment/systemd/hermes-expense-dashboard.service"
unit_dir="${XDG_CONFIG_HOME:-${HOME}/.config}/systemd/user"
unit_file="${unit_dir}/hermes-expense-dashboard.service"

if [[ ! -f "${env_file}" ]]; then
    printf 'Create %s from .env.example before installation.\n' "${env_file}" >&2
    exit 1
fi
if grep -q '^EXPENSE_WEB_ACCESS_TOKEN=replace-with-a-generated-token$' "${env_file}"; then
    printf 'Generate and set EXPENSE_WEB_ACCESS_TOKEN before installation.\n' >&2
    printf 'Command: ./gradlew installDist && build/install/hermes-expense-ledger/bin/hermes-expense-ledger generate-token\n' >&2
    exit 1
fi

java_bin="$(command -v java || true)"
if [[ -z "${java_bin}" ]]; then
    printf 'Java is not available in PATH. Install Java 22 before installation.\n' >&2
    exit 1
fi
java_home="$(dirname -- "$(dirname -- "$(readlink -f -- "${java_bin}")")")"

"${project_dir}/gradlew" clean check installDist
mkdir -p "${unit_dir}"
escaped_project_dir="${project_dir//\\/\\\\}"
escaped_project_dir="${escaped_project_dir//&/\\&}"
escaped_project_dir="${escaped_project_dir//|/\\|}"
escaped_java_home="${java_home//\\/\\\\}"
escaped_java_home="${escaped_java_home//&/\\&}"
escaped_java_home="${escaped_java_home//|/\\|}"
sed -e "s|__PROJECT_DIR__|${escaped_project_dir}|g" \
    -e "s|__JAVA_HOME__|${escaped_java_home}|g" \
    "${unit_template}" > "${unit_file}"
chmod 600 "${unit_file}"

systemctl --user daemon-reload
systemctl --user enable --now hermes-expense-dashboard.service
systemctl --user --no-pager status hermes-expense-dashboard.service
