#!/usr/bin/env python3
"""Run a Mobet workflow against an Appium Android session.

This is intentionally a standard-library-only CLI. It is not part of the Android
APK, so its Appium HTTP connection does not weaken the APK's no-network
permission invariant. It consumes the same workflow JSON accepted by Mobet and
emits a CI-friendly JUnit XML report.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Any

DEFAULT_ACTIONS = {
    "wait", "tap", "fill", "scroll", "delay", "confirm", "back", "home", "launch",
    "capture", "tappoint", "swipe", "ocrwait", "visualtap", "tryalternates",
}
VISUAL_ACTIONS = {"capture", "tappoint", "swipe", "ocrwait", "visualtap"}
W3C_ELEMENT_KEY = "element-6066-11e4-a52e-4f735466cecf"


class RunnerError(RuntimeError):
    pass


class AppiumError(RunnerError):
    pass


@dataclass
class TestCase:
    name: str
    duration: float
    failure: str | None = None


class JUnitXmlReporter:
    """Writes one workflow result in the XML shape understood by common CI systems."""

    @staticmethod
    def write(path: Path, suite_name: str, case: TestCase) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        suite = ET.Element(
            "testsuite",
            name=suite_name,
            tests="1",
            failures="1" if case.failure else "0",
            errors="0",
            skipped="0",
            time=f"{case.duration:.3f}",
        )
        testcase = ET.SubElement(
            suite,
            "testcase",
            classname="ai.arena.mobet.headless",
            name=case.name,
            time=f"{case.duration:.3f}",
        )
        if case.failure:
            failure = ET.SubElement(testcase, "failure", message=case.failure[:500])
            failure.text = case.failure
        tree = ET.ElementTree(ET.Element("testsuites"))
        tree.getroot().append(suite)
        ET.indent(tree, space="  ")
        tree.write(path, encoding="utf-8", xml_declaration=True)


class AppiumSession:
    """Small W3C WebDriver client for the operations a workflow needs."""

    def __init__(self, server_url: str, package_name: str, no_reset: bool = True):
        self.server_url = server_url.rstrip("/")
        self.package_name = package_name
        self.no_reset = no_reset
        self.session_id: str | None = None

    def _request(self, method: str, path: str, body: Any = None) -> dict[str, Any]:
        url = self.server_url + path
        encoded = None
        headers = {"Accept": "application/json"}
        if body is not None:
            encoded = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json; charset=utf-8"
        request = urllib.request.Request(url, data=encoded, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                payload = json.loads(response.read().decode("utf-8") or "{}")
        except (urllib.error.URLError, TimeoutError) as error:
            raise AppiumError(f"Appium request {method} {path} failed: {error}") from error
        except json.JSONDecodeError as error:
            raise AppiumError(f"Appium returned invalid JSON for {method} {path}") from error
        value = payload.get("value")
        if isinstance(value, dict) and value.get("error"):
            raise AppiumError(value.get("message") or value["error"])
        return payload

    def start(self) -> None:
        payload = self._request(
            "POST",
            "/session",
            {
                "capabilities": {
                    "alwaysMatch": {
                        "platformName": "Android",
                        "appium:automationName": "UiAutomator2",
                        "appium:appPackage": self.package_name,
                        "appium:noReset": self.no_reset,
                    }
                }
            },
        )
        value = payload.get("value", {})
        self.session_id = payload.get("sessionId") or value.get("sessionId")
        if not self.session_id:
            raise AppiumError("Appium created a session without a session id")

    def close(self) -> None:
        if self.session_id:
            try:
                self._request("DELETE", f"/session/{self.session_id}")
            finally:
                self.session_id = None

    def _session(self, method: str, suffix: str, body: Any = None) -> Any:
        if not self.session_id:
            raise AppiumError("Appium session is not active")
        return self._request(method, f"/session/{self.session_id}{suffix}", body).get("value")

    @staticmethod
    def _selector(selector: dict[str, Any]) -> tuple[str, str]:
        view_id = selector.get("viewId")
        if view_id:
            return "id", str(view_id)
        description = selector.get("description")
        if description:
            return "accessibility id", str(description)
        text = selector.get("text")
        if text:
            escaped = str(text).replace("\\", "\\\\").replace('"', '\\"')
            return "-android uiautomator", f'new UiSelector().textContains("{escaped}")'
        raise RunnerError("selector has no text, viewId, or description")

    def find(self, selector: dict[str, Any], timeout_ms: int) -> str:
        using, value = self._selector(selector)
        deadline = time.monotonic() + timeout_ms / 1000.0
        last_error: Exception | None = None
        while time.monotonic() <= deadline:
            try:
                result = self._session("POST", "/element", {"using": using, "value": value})
                if isinstance(result, dict):
                    element = result.get(W3C_ELEMENT_KEY) or result.get("ELEMENT")
                    if element:
                        return str(element)
            except AppiumError as error:
                last_error = error
            time.sleep(0.1)
        suffix = f": {last_error}" if last_error else ""
        raise RunnerError(f"timed out finding {using}={value}{suffix}")

    def click(self, element: str) -> None:
        self._session("POST", f"/element/{element}/click")

    def set_text(self, element: str, value: str) -> None:
        self._session(
            "POST",
            f"/element/{element}/value",
            {"text": value, "value": list(value)},
        )

    def scroll(self, element: str) -> None:
        self._session(
            "POST",
            "/execute/sync",
            {"script": "mobile: scrollGesture", "args": [
                {"elementId": element, "direction": "down", "percent": 0.8}
            ]},
        )

    def back(self) -> None:
        self._session("POST", "/back", {})

    def home(self) -> None:
        self._session(
            "POST",
            "/execute/sync",
            {"script": "mobile: pressKey", "args": [{"keycode": 3}]},
        )

    def launch(self, package_name: str) -> None:
        self._session(
            "POST",
            "/appium/device/activate_app",
            {"appId": package_name},
        )

    def active_package(self) -> str | None:
        result = self._session("GET", "/appium/device/current_package")
        return str(result) if result else None

    def page_source(self) -> str:
        return str(self._session("GET", "/source") or "")

    def screenshot(self, path: Path) -> None:
        encoded = self._session("GET", "/screenshot")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(base64.b64decode(encoded))


def selector_from_step(step: dict[str, Any]) -> dict[str, Any]:
    return {
        key: step[key]
        for key in ("text", "viewId", "description")
        if step.get(key) not in (None, "")
    }


def require_policy(workflow: dict[str, Any]) -> tuple[str, dict[str, Any]]:
    package_name = workflow.get("package")
    if not package_name:
        raise RunnerError("workflow.package is required")
    policy = workflow.get("policy") or {}
    allowed_packages = set(policy.get("allowedPackages") or [package_name])
    if package_name not in allowed_packages:
        raise RunnerError("target package is not in policy.allowedPackages")
    allowed_actions = set(policy.get("allowedActions") or DEFAULT_ACTIONS)
    steps = workflow.get("steps") or []
    max_actions = int(policy.get("maxActions", 50))
    if len(steps) > max_actions:
        raise RunnerError(f"workflow has {len(steps)} steps; limit is {max_actions}")
    for index, step in enumerate(steps, 1):
        action = str(step.get("action", "")).lower()
        if action not in allowed_actions:
            raise RunnerError(f"step {index}: action {action!r} is not allowed")
        if action in VISUAL_ACTIONS and not policy.get("allowVisualFallbacks", False):
            raise RunnerError(f"step {index}: visual fallback is disabled")
        if action == "launch" and step.get("package") not in allowed_packages:
            raise RunnerError(f"step {index}: launch package is outside the allowlist")
    return str(package_name), policy


def check_expectation(
    session: AppiumSession,
    expectation: dict[str, Any] | None,
    before: str,
    package_name: str,
) -> None:
    if not expectation:
        return
    after = session.page_source()
    haystack = after.casefold()
    present = expectation.get("textPresent")
    absent = expectation.get("textAbsent")
    if expectation.get("screenChange") and hashlib.sha256(before.encode()).digest() == hashlib.sha256(after.encode()).digest():
        raise RunnerError("expectation failed: screen did not change")
    if present and str(present).casefold() not in haystack:
        raise RunnerError(f"expectation failed: text {present!r} is absent")
    if absent and str(absent).casefold() in haystack:
        raise RunnerError(f"expectation failed: text {absent!r} is present")
    expected_package = expectation.get("package")
    if expected_package and session.active_package() != expected_package:
        raise RunnerError(
            f"expectation failed: expected package {expected_package}, found {session.active_package()}"
        )
    if expected_package and expected_package != package_name:
        raise RunnerError("expectation package is outside the target package")


def execute_step(
    session: AppiumSession,
    step: dict[str, Any],
    package_name: str,
    approve: bool,
    captures: Path,
) -> None:
    action = str(step.get("action", "")).lower()
    timeout_ms = int(step.get("timeoutMs", 5000))
    selector = selector_from_step(step)
    before = session.page_source()

    if action == "delay":
        time.sleep(max(0, int(step.get("delayMs", 300))) / 1000.0)
    elif action == "wait":
        session.find(selector, timeout_ms)
    elif action == "tap":
        session.click(session.find(selector, timeout_ms))
    elif action == "fill":
        session.set_text(session.find(selector, timeout_ms), str(step.get("value", "")))
    elif action == "scroll":
        session.scroll(session.find(selector, timeout_ms))
    elif action == "tryalternates":
        options = step.get("options") or []
        error: Exception | None = None
        for option in options:
            try:
                session.click(session.find(option, timeout_ms))
                error = None
                break
            except RunnerError as candidate_error:
                error = candidate_error
        if error:
            raise error
    elif action == "back":
        session.back()
    elif action == "home":
        session.home()
    elif action == "launch":
        target = step.get("package")
        if not target:
            raise RunnerError("launch requires package")
        if target != package_name:
            raise RunnerError("launch target is outside the target package")
        session.launch(target)
    elif action == "confirm":
        if not approve:
            raise RunnerError("confirmation required; rerun with --approve")
    elif action == "capture":
        stamp = int(time.time() * 1000)
        session.screenshot(captures / f"capture-{stamp}.png")
    else:
        raise RunnerError(f"action {action!r} is not supported by the Appium CLI driver")

    check_expectation(session, step.get("expect"), before, package_name)
    delay_ms = int(step.get("delayMs", 300))
    if action != "delay" and delay_ms > 0:
        time.sleep(delay_ms / 1000.0)


def run_workflow(
    workflow: dict[str, Any],
    server_url: str,
    approve: bool,
    captures: Path,
) -> tuple[bool, str, float]:
    package_name, _policy = require_policy(workflow)
    session = AppiumSession(server_url, package_name)
    started = time.monotonic()
    try:
        session.start()
        for index, raw_step in enumerate(workflow.get("steps", []), 1):
            step = dict(raw_step)
            action = str(step.get("action", "")).lower()
            if step.get("ifText") and _find_optional(session, {"text": step["ifText"]}, int(step.get("timeoutMs", 5000))):
                pass
            elif step.get("ifText"):
                continue
            if step.get("unlessText") and _find_optional(session, {"text": step["unlessText"]}, int(step.get("timeoutMs", 5000))):
                continue
            retries = int(step.get("retries", 0))
            for attempt in range(retries + 1):
                try:
                    execute_step(session, step, package_name, approve, captures)
                    break
                except RunnerError:
                    if attempt >= retries:
                        raise
        return True, f"Completed {len(workflow.get('steps', []))} steps", time.monotonic() - started
    except (RunnerError, AppiumError) as error:
        return False, str(error), time.monotonic() - started
    finally:
        session.close()


def _find_optional(session: AppiumSession, selector: dict[str, Any], timeout_ms: int) -> bool:
    try:
        session.find(selector, timeout_ms)
        return True
    except RunnerError:
        return False


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run a Mobet workflow through Appium")
    parser.add_argument("workflow", type=Path, help="workflow JSON file")
    parser.add_argument("--server-url", default=os.getenv("MOBET_APPIUM_URL", "http://127.0.0.1:4723"))
    parser.add_argument("--junit-xml", type=Path, default=Path("mobet-test-results.xml"))
    parser.add_argument("--captures", type=Path, default=Path("mobet-captures"))
    parser.add_argument("--approve", action="store_true", help="approve confirm steps in non-interactive CI")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    started = time.monotonic()
    try:
        workflow = json.loads(args.workflow.read_text(encoding="utf-8"))
        success, message, duration = run_workflow(
            workflow, args.server_url, args.approve, args.captures
        )
    except (OSError, json.JSONDecodeError, RunnerError) as error:
        success, message, duration = False, str(error), time.monotonic() - started
    case = TestCase(str(workflow.get("name", args.workflow.stem)) if "workflow" in locals() else args.workflow.stem, duration, None if success else message)
    JUnitXmlReporter.write(args.junit_xml, "mobet", case)
    print(message)
    return 0 if success else 1


if __name__ == "__main__":
    raise SystemExit(main())
