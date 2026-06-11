#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODELSCOPE_CACHE="${MODELSCOPE_CACHE:-${ROOT_DIR}/.models/modelscope}"
VENV_DIR="${ROOT_DIR}/.models/.venv"
PYTHON_BIN="${PYTHON_BIN:-python3}"

export MODELSCOPE_CACHE

mkdir -p "${MODELSCOPE_CACHE}"

if [ ! -x "${VENV_DIR}/bin/python" ]; then
  "${PYTHON_BIN}" -m venv "${VENV_DIR}"
fi

"${VENV_DIR}/bin/python" -m pip install --upgrade pip
"${VENV_DIR}/bin/python" -m pip install "modelscope==1.23.2"

"${VENV_DIR}/bin/python" "${ROOT_DIR}/sidecar/download_models.py"
