#!/usr/bin/env bash
set -eux

NODE_BIN="${NODE_BIN:-$(command -v node || true)}"
PYTHON_BIN="${PYTHON_BIN:-$(command -v python3 || true)}"
APPIUM_BIN="${APPIUM_BIN:-$(command -v appium || true)}"
if test -z "$APPIUM_BIN" && test -n "$(command -v npm || true)"; then
  APPIUM_BIN="$(npm prefix --global)/bin/appium"
fi

mkdir -p headless-test-results
trap 'status=$?; printf "status=%s\nNODE_BIN=%s\nPYTHON_BIN=%s\nAPPIUM_BIN=%s\nPATH=%s\n" "$status" "${NODE_BIN:-}" "${PYTHON_BIN:-}" "${APPIUM_BIN:-}" "$PATH" > headless-test-results/diagnostics.txt; test -z "${APPIUM_PID:-}" || kill "$APPIUM_PID" || true; exit "$status"' EXIT

test -x "$NODE_BIN"
test -x "$PYTHON_BIN"
test -x "$APPIUM_BIN"
"$NODE_BIN" "$APPIUM_BIN" --address 127.0.0.1 --port 4723 > /tmp/appium.log 2>&1 &
APPIUM_PID=$!

"$PYTHON_BIN" - <<'PY'
import time
import urllib.request

deadline = time.time() + 60
while time.time() < deadline:
    try:
        urllib.request.urlopen('http://127.0.0.1:4723/status', timeout=2)
        break
    except Exception:
        time.sleep(1)
else:
    raise SystemExit('Appium did not become ready')
PY

"$PYTHON_BIN" scripts/mobet-runner.py \
  app/src/main/assets/sample_workflow.json \
  --server-url http://127.0.0.1:4723 \
  --junit-xml headless-test-results/mobet.xml \
  --captures headless-test-results/captures \
  --approve
