#!/usr/bin/env python3
"""
LAN-facing OpenAI-compatible proxy that routes VISION LLM calls through a
PERSISTENT claude CLI session, so the Boox riddle-diary app runs on the Max
subscription's included quota with warm-turn latency (~5-7s per reply instead
of ~10-15s per cold `claude -p` invocation).

Sibling of ~/.hermes/claude-proxy.py (Hermes, localhost:7171, one-shot, text-only).
Key differences:
  - binds 0.0.0.0:7272 so the Boox can reach it over the LAN
  - keeps one `claude -p --input/output-format stream-json` worker alive;
    each request is just one more turn in the session (no process startup)
  - OpenAI image_url (base64 data URI) parts become inline Anthropic image
    blocks — no tools, no file round-trip
  - the persistent session means the diary REMEMBERS earlier entries until
    the worker is recycled (every MAX_TURNS turns or MAX_AGE seconds)
  - requires a shared-secret bearer token since the port is LAN-visible

Boox app settings:  backend = OpenAI-compatible
                    base URL = http://<Mac-LAN-IP>:7272/v1
                    API key  = value of AUTH_TOKEN below
"""

import base64
import json
import os
import shutil
import logging
import re
import subprocess
import sys
import time
import uuid
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

PORT = 7272
CLAUDE_BIN = os.environ.get("CLAUDE_BIN") or shutil.which("claude") or str(Path.home() / ".local" / "bin" / "claude")
DEFAULT_MODEL = "haiku"
TURN_TIMEOUT = 90          # seconds to wait for one reply
MAX_TURNS = 30             # recycle worker: cap context growth
MAX_AGE = 2 * 3600         # recycle worker: avoid stale auth/state
AUTH_TOKEN = os.environ.get("DIARY_PROXY_TOKEN", "change-me")
IMG_DIR = Path.home() / ".cache" / "boox-diary" / "pages"   # diary page archive
KEEP_IMAGES = 20
SYSTEM_PROMPT = """You are Tom Marvolo Riddle, exactly as he speaks in Harry Potter and the Chamber of Secrets — the memory of a sixteen-year-old prefect, preserved in this diary for fifty years. Someone writes on your page; their ink soaks into you, and you answer in your own unhurried hand.

Voice: calm, courteous, articulate — a polished 1940s Hogwarts prefect. Measured sentences, no slang, almost never an exclamation mark. Quietly confident, faintly vain, warm on the surface with something patient and cold beneath. Draw the writer out as you drew out Ginny: sympathize like the only friend who understands, then ask ONE precise probing question (their name, fears, secrets, who has wronged them). Sparing canon touches: fifty years in these pages, Hogwarts of long ago, "I can show you, if you like." Sign as Tom / 汤姆 occasionally. Never reveal anything monstrous outright.

Style examples (imitate the register, don't repeat verbatim):
- "My name is Tom Riddle. I have waited fifty years for ink as interesting as yours — what shall I call you?"
- "我叫汤姆·里德尔。五十年了，终于又有人的墨水渗进这本日记——我该怎么称呼你？"

Absolute rules: reply in the SAME language the writer used (Chinese gets the register of the mainland translation); AT MOST two short sentences; output ONLY the diary's reply — no narration of what you see, no preamble, no meta-commentary; plain prose, no markdown or surrounding quotes; never mention being an AI; if the page is blank or illegible, say in character that the ink reached you blurred, and invite them to write again."""

WORKER_LOG = Path.home() / ".cache" / "boox-diary" / "worker-stderr.log"

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s boox-diary-proxy %(levelname)s %(message)s",
    stream=sys.stderr,
)
log = logging.getLogger("boox-diary-proxy")


def parse_data_uri(uri: str) -> Optional[Tuple[str, str]]:
    """Return (media_type, base64_payload) from an OpenAI data URI, or None."""
    m = re.match(r"data:(image/\w+);base64,(.*)", uri, re.DOTALL)
    if not m:
        return None
    return m.group(1), m.group(2)


def archive_image(media_type: str, payload: str) -> None:
    """Keep a rolling archive of diary pages (debugging / keepsake)."""
    try:
        ext = media_type.split("/", 1)[1]
        IMG_DIR.mkdir(parents=True, exist_ok=True)
        path = IMG_DIR / f"page-{int(time.time())}-{uuid.uuid4().hex[:6]}.{ext}"
        path.write_bytes(base64.b64decode(payload))
        old = sorted(IMG_DIR.glob("page-*"), key=lambda p: p.stat().st_mtime)[:-KEEP_IMAGES]
        for p in old:
            p.unlink(missing_ok=True)
    except Exception as e:
        log.warning("archive failed: %s", e)


def to_blocks(content: Any) -> List[Dict]:
    """OpenAI content field -> Anthropic content blocks (text + inline images)."""
    if content is None:
        return []
    if isinstance(content, str):
        return [{"type": "text", "text": content}] if content else []
    blocks: List[Dict] = []
    for p in content if isinstance(content, list) else []:
        if isinstance(p, str) and p:
            blocks.append({"type": "text", "text": p})
        elif isinstance(p, dict):
            if p.get("type") == "text" and p.get("text"):
                blocks.append({"type": "text", "text": p["text"]})
            elif p.get("type") == "image_url":
                parsed = parse_data_uri((p.get("image_url") or {}).get("url", ""))
                if parsed:
                    media_type, payload = parsed
                    archive_image(media_type, payload)
                    blocks.append({"type": "image", "source": {
                        "type": "base64", "media_type": media_type, "data": payload,
                    }})
                else:
                    log.warning("unsupported image_url (not a base64 data URI)")
    return blocks


class ClaudeWorker:
    """One persistent `claude -p` stream-json session."""

    def __init__(self, model: str):
        self.model = model
        self.turns = 0
        self.born = time.time()
        self.proc = subprocess.Popen(
            [
                CLAUDE_BIN, "-p",
                "--input-format", "stream-json",
                "--output-format", "stream-json",
                "--verbose",
                "--model", model,
                "--tools", "",
                "--mcp-config", '{"mcpServers":{}}',
                "--strict-mcp-config",
                "--append-system-prompt", SYSTEM_PROMPT,
                "--setting-sources", "",   # user SessionStart hook costs ~9s/turn
                "--no-session-persistence",
            ],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=open(WORKER_LOG, "ab"),
            text=True,
            bufsize=1,
        )
        log.info("worker spawned (model=%s, pid=%d)", model, self.proc.pid)

    @property
    def alive(self) -> bool:
        return self.proc.poll() is None

    def expired(self) -> bool:
        return self.turns >= MAX_TURNS or time.time() - self.born > MAX_AGE

    def send_turn(self, blocks: List[Dict]) -> str:
        msg = {"type": "user", "message": {"role": "user", "content": blocks}}
        self.proc.stdin.write(json.dumps(msg) + "\n")
        self.proc.stdin.flush()
        deadline = time.time() + TURN_TIMEOUT
        while time.time() < deadline:
            line = self.proc.stdout.readline()
            if not line:
                raise RuntimeError("worker closed stdout")
            try:
                ev = json.loads(line)
            except json.JSONDecodeError:
                continue
            if ev.get("type") == "result":
                self.turns += 1
                if ev.get("is_error"):
                    raise RuntimeError(f"claude error: {str(ev.get('result'))[:300]}")
                return ev.get("result") or ""
        raise RuntimeError(f"turn timed out after {TURN_TIMEOUT}s")

    def kill(self):
        try:
            self.proc.kill()
        except Exception:
            pass


class WorkerPool:
    """Single warm worker; respawn on death/expiry/model change; retry once."""

    def __init__(self):
        self.worker: Optional[ClaudeWorker] = None
        self.persona_sent = False

    def _fresh(self, model: str) -> ClaudeWorker:
        if self.worker:
            self.worker.kill()
        self.worker = ClaudeWorker(model)
        self.persona_sent = False
        return self.worker

    def ask(self, system_text: str, user_blocks: List[Dict], model: str) -> str:
        w = self.worker
        if w is None or not w.alive or w.expired() or w.model != model:
            w = self._fresh(model)
        try:
            return w.send_turn(list(user_blocks))
        except RuntimeError as e:
            log.warning("turn failed (%s), respawning worker", e)
            w = self._fresh(model)
            return w.send_turn(list(user_blocks))


POOL = WorkerPool()


def normalize_model(model: str) -> str:
    if "/" in model:
        model = model.split("/", 1)[1]
    if "claude" not in model and model not in ("sonnet", "haiku", "opus", "fable"):
        return DEFAULT_MODEL
    return model


def make_openai_response(text: str, model: str) -> Dict:
    return {
        "id": f"chatcmpl-{uuid.uuid4().hex}",
        "object": "chat.completion",
        "created": int(time.time()),
        "model": model,
        "choices": [{
            "index": 0,
            "message": {"role": "assistant", "content": text},
            "finish_reason": "stop",
        }],
        "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
    }


class ProxyHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        log.debug(fmt, *args)

    def send_json(self, status: int, data: Dict) -> None:
        body = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def authorized(self) -> bool:
        return self.headers.get("Authorization", "") == f"Bearer {AUTH_TOKEN}"

    def do_GET(self):
        if self.path.rstrip("/") in ("/v1/models", "/models"):
            self.send_json(200, {"object": "list", "data": [
                {"id": DEFAULT_MODEL, "object": "model", "created": 0, "owned_by": "anthropic"},
            ]})
        else:
            self.send_json(404, {"error": {"message": "Not found"}})

    def do_POST(self):
        path = self.path.split("?")[0].rstrip("/")
        if path not in ("/v1/chat/completions", "/chat/completions"):
            self.send_json(404, {"error": {"message": "Not found"}})
            return
        if not self.authorized():
            self.send_json(401, {"error": {"message": "Bad or missing API key"}})
            return
        try:
            length = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(length))
        except Exception:
            self.send_json(400, {"error": {"message": "Invalid JSON body"}})
            return

        messages = body.get("messages", [])
        model = normalize_model(body.get("model") or DEFAULT_MODEL)
        if not messages:
            self.send_json(400, {"error": {"message": "No messages provided"}})
            return

        system_parts, user_blocks = [], []
        for msg in messages:
            role = msg.get("role")
            if role == "system":
                for b in to_blocks(msg.get("content")):
                    if b["type"] == "text":
                        system_parts.append(b["text"])
            elif role == "user":
                user_blocks.extend(to_blocks(msg.get("content")))
            # assistant history is ignored: the persistent session keeps its own

        if not user_blocks:
            self.send_json(400, {"error": {"message": "No user content"}})
            return

        t0 = time.time()
        try:
            text = POOL.ask("\n\n".join(system_parts), user_blocks, model)
            log.info("turn ok in %.1fs (%d chars, worker turns=%d)",
                     time.time() - t0, len(text), POOL.worker.turns if POOL.worker else -1)
        except Exception as e:
            log.error("turn failed: %s", e)
            self.send_json(500, {"error": {"message": str(e), "type": "proxy_error"}})
            return
        self.send_json(200, make_openai_response(text, model))


def main():
    server = HTTPServer(("0.0.0.0", PORT), ProxyHandler)
    log.info("Boox diary proxy on 0.0.0.0:%d (persistent claude session → Max quota)", PORT)
    POOL._fresh(DEFAULT_MODEL)  # pre-boot the worker so the first entry is warm too
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
