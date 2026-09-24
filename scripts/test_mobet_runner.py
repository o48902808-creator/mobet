#!/usr/bin/env python3
"""Protocol-level tests for the Appium CLI without requiring an emulator."""

from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


SCRIPT = Path(__file__).with_name("mobet-runner.py")
SPEC = importlib.util.spec_from_file_location("mobet_runner", SCRIPT)
assert SPEC and SPEC.loader
mobet_runner = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = mobet_runner
SPEC.loader.exec_module(mobet_runner)


class FakeAppiumState:
    screen = "landing"
    entered = ""
    closed = False


class FakeAppiumHandler(BaseHTTPRequestHandler):
    state = FakeAppiumState()

    def log_message(self, *_args):
        return

    def reply(self, value=None, status=200):
        payload = {"value": value}
        encoded = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def body(self):
        size = int(self.headers.get("Content-Length", "0"))
        return json.loads(self.rfile.read(size) or b"{}")

    def do_POST(self):
        body = self.body()
        if self.path == "/session":
            self.reply({"sessionId": "fake-session"})
        elif self.path == "/session/fake-session/element":
            value = body.get("value", "")
            if "Continue" in value and self.state.screen == "landing":
                self.reply({"element-6066-11e4-a52e-4f735466cecf": "continue"})
            elif "form/email" in value and self.state.screen == "form":
                self.reply({"element-6066-11e4-a52e-4f735466cecf": "email"})
            elif "Save" in value and self.state.screen == "form":
                self.reply({"element-6066-11e4-a52e-4f735466cecf": "save"})
            else:
                self.reply({"error": "no such element", "message": "not on this screen"}, 404)
        elif self.path.endswith("/element/continue/click"):
            self.state.screen = "form"
            self.reply(None)
        elif self.path.endswith("/element/save/click"):
            self.state.screen = "done"
            self.reply(None)
        elif self.path.endswith("/element/email/value"):
            self.state.entered = body.get("text", "")
            self.reply(None)
        else:
            self.reply(None)

    def do_GET(self):
        if self.path == "/session/fake-session/source":
            labels = {
                "landing": "Continue",
                "form": "Email Save",
                "done": "Done",
            }
            self.reply(f"<hierarchy text=\"{labels[self.state.screen]}\"/>")
        elif self.path == "/session/fake-session/appium/device/current_package":
            self.reply("com.example.demo")
        else:
            self.reply("")

    def do_DELETE(self):
        self.state.closed = True
        self.reply(None)


class MobetRunnerCliTest(unittest.TestCase):
    def test_workflow_runs_over_w3c_appium_and_writes_junit_xml(self):
        FakeAppiumHandler.state = FakeAppiumState()
        server = ThreadingHTTPServer(("127.0.0.1", 0), FakeAppiumHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            workflow = {
                "name": "CLI profile",
                "package": "com.example.demo",
                "variables": {"continue": "Continue"},
                "policy": {
                    "allowedPackages": ["com.example.demo"],
                    "allowedActions": ["tap", "fill", "branch", "delay"],
                    "maxActions": 5,
                    "maxRuntimeMs": 10000,
                },
                "steps": [
                    {"action": "tap", "text": "{{var:continue}}", "delayMs": 0,
                     "expect": {"screenChange": True, "textPresent": "Email"}},
                    {"action": "fill", "viewId": "form/email", "value": "ada@example.com",
                     "delayMs": 0, "expect": {"textPresent": "Email"}},
                    {"action": "branch", "expect": {"textPresent": "Email"},
                     "goto": "save", "elseGoto": "fallback", "delayMs": 0},
                    {"action": "delay", "label": "fallback", "delayMs": 0},
                    {"action": "tap", "label": "save", "text": "Save", "delayMs": 0,
                     "expect": {"screenChange": True, "textPresent": "Done"}},
                ],
            }
            with tempfile.TemporaryDirectory() as directory:
                report = Path(directory) / "junit.xml"
                ok, message, _duration = mobet_runner.run_workflow(
                    workflow,
                    f"http://127.0.0.1:{server.server_port}",
                    False,
                    Path(directory) / "captures",
                )
                mobet_runner.JUnitXmlReporter.write(
                    report, "mobet", mobet_runner.TestCase("CLI profile", 0.01, None if ok else message)
                )
                self.assertTrue(ok, message)
                self.assertEqual("Completed 5 steps", message)
                self.assertEqual("ada@example.com", FakeAppiumHandler.state.entered)
                self.assertTrue(FakeAppiumHandler.state.closed)
                self.assertEqual("0", __import__("xml.etree.ElementTree", fromlist=["parse"]).parse(report).find("testsuite").get("failures"))
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
