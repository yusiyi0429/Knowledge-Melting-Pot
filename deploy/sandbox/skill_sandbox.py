#!/usr/bin/env python3
"""No-egress declarative Skill runner. It never evaluates source code."""

import json
import unicodedata
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_REQUEST_BYTES = 64 * 1024
MAX_INPUT_CHARS = 20_000
MAX_RULES = 100
MAX_TOKENS = 20


class ProtocolError(Exception):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def _text(value, maximum: int) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > maximum:
        raise ProtocolError("SANDBOX_SCHEMA_INVALID")
    return value.strip()


def _normalized(value: str) -> str:
    return "".join(unicodedata.normalize("NFKC", value).split()).lower()


def execute(payload: object) -> str:
    if not isinstance(payload, dict) or set(payload) != {"version", "invocationId", "program", "input"}:
        raise ProtocolError("SANDBOX_SCHEMA_INVALID")
    if payload["version"] != 1:
        raise ProtocolError("SANDBOX_VERSION_UNSUPPORTED")
    _text(payload["invocationId"], 200)
    input_text = _text(payload["input"], MAX_INPUT_CHARS)
    program = payload["program"]
    if not isinstance(program, dict) or set(program) != {"kind", "rules", "defaultPrediction"}:
        raise ProtocolError("SANDBOX_PROGRAM_INVALID")
    if program["kind"] != "CLASSIFY_CONTAINS":
        raise ProtocolError("SANDBOX_PROGRAM_UNSUPPORTED")
    default_prediction = _text(program["defaultPrediction"], 500)
    rules = program["rules"]
    if not isinstance(rules, list) or not 1 <= len(rules) <= MAX_RULES:
        raise ProtocolError("SANDBOX_PROGRAM_INVALID")
    normalized_input = _normalized(input_text)
    for rule in rules:
        if not isinstance(rule, dict) or set(rule) != {"containsAny", "prediction"}:
            raise ProtocolError("SANDBOX_PROGRAM_INVALID")
        tokens = rule["containsAny"]
        if not isinstance(tokens, list) or not 1 <= len(tokens) <= MAX_TOKENS:
            raise ProtocolError("SANDBOX_PROGRAM_INVALID")
        prediction = _text(rule["prediction"], 500)
        normalized_tokens = [_normalized(_text(token, 200)) for token in tokens]
        if any(token in normalized_input for token in normalized_tokens):
            return prediction
    return default_prediction


class Handler(BaseHTTPRequestHandler):
    server_version = "kmp-sandbox"
    sys_version = ""

    def do_GET(self):
        if self.path == "/health":
            self._json(200, {"status": "UP"})
        else:
            self._json(404, {"code": "NOT_FOUND"})

    def do_POST(self):
        if self.path != "/v1/execute":
            self._json(404, {"code": "NOT_FOUND"})
            return
        content_type = self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
        try:
            length = int(self.headers.get("Content-Length", "-1"))
        except ValueError:
            length = -1
        if content_type != "application/json" or length < 1 or length > MAX_REQUEST_BYTES:
            self._json(413, {"code": "SANDBOX_REQUEST_REJECTED"})
            return
        try:
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
            prediction = execute(payload)
            self._json(200, {"prediction": prediction})
        except (UnicodeDecodeError, json.JSONDecodeError):
            self._json(422, {"code": "SANDBOX_JSON_INVALID"})
        except ProtocolError as error:
            self._json(422, {"code": error.code})

    def _json(self, status: int, value: dict):
        body = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        # Never log Skill programs or business inputs.
        return


class Server(ThreadingHTTPServer):
    daemon_threads = True


if __name__ == "__main__":
    Server(("0.0.0.0", 8081), Handler).serve_forever()
