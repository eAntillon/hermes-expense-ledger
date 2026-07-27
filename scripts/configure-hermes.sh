#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="${EXPENSE_ENV_FILE:-${project_dir}/.env}"
library_dir="${project_dir}/build/install/hermes-expense-ledger/lib"

if [[ ! -f "${env_file}" ]]; then
    printf 'Missing configuration file: %s\n' "${env_file}" >&2
    exit 1
fi
if [[ ! -d "${library_dir}" ]]; then
    printf 'Missing application distribution. Run ./gradlew installDist first.\n' >&2
    exit 1
fi

set -a
# shellcheck disable=SC1090
source "${env_file}"
set +a

exec java -cp "${library_dir}/*" dev.eantillon.expenseledger.integration.HermesConfigurator
