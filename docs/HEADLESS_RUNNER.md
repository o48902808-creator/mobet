# Headless CI runner

The entry point is `scripts/mobet-runner.py`. It runs a Mobet workflow against an Appium server using only Python's standard
library, so the CLI is separate from the Android APK and does not change the
APK's no-network permission boundary.

## Usage

Start an Appium server with the Android UiAutomator2 backend, then run:

```bash
python3 scripts/mobet-runner.py \
  path/to/workflow.json \
  --server-url http://127.0.0.1:4723 \
  --junit-xml build/test-results/mobet.xml \
  --captures build/mobet-captures \
  --approve
```

The process exits zero only when every workflow step and declared expectation
passes. It writes a JUnit XML report on both success and failure, so a CI job
can publish or collect the report without parsing console output. `--approve`
is required for `confirm` steps because the CLI has no interactive Android UI;
CI should only set it when approval is part of that job's authorization policy.

The CLI supports the ordinary accessibility actions (`wait`, `tap`, `fill`,
`scroll`, `delay`, `back`, `home`, `launch`, `capture`, and
`tryAlternates`). Visual OCR/coordinate actions remain explicit unsupported
failures in this driver rather than silently degrading to a guessed tap.

## Drivers

- `AccessibilityDriver` is the production service driver.
- `UiAutomatorDeviceDriver` is an instrumentation-only `DeviceDriver` backed by
  AndroidX UiAutomator. It is kept out of the production APK.
- `scripts/mobet-runner.py` uses the W3C Appium protocol for external CI.

All three routes target the same `DeviceDriver` seam; they do not duplicate
workflow policy inside the drivers.

The `Headless Appium smoke` workflow runs the bundled Settings demo on an API
29 emulator, starts Appium with UiAutomator2, publishes the JUnit XML report,
and fails the job when the workflow or its expectations fail. It can be run
manually from GitHub Actions and also runs for pull requests.
