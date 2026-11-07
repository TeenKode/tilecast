"""On-demand RTSP/RTMP -> HLS bridge for Tilecast's Video Stream widget.

Deployment must restrict the listener to the signage network. Player links are
bearer capabilities: a leaked URL grants access until STREAM_GATEWAY_KEY rotates.
"""
from __future__ import annotations

import base64
import hashlib
import ipaddress
import json
import mimetypes
import os
from pathlib import Path
import re
import shutil
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT = Path(os.getenv("STREAM_HLS_DIR", "/tmp/tilecast-streams"))
HTML = Path(__file__).with_name("player.html").read_text("utf-8")
HLS_JS = Path(__file__).with_name("hls.min.js")
HOSTS = {s.strip().lower().rstrip(".") for s in os.getenv("STREAM_ALLOWED_HOSTS", "").split(",") if s.strip()}
MAX_STREAMS = min(max(int(os.getenv("STREAM_MAX_STREAMS", "8")), 1), 64)
IDLE_SECONDS = min(max(int(os.getenv("STREAM_IDLE_SECONDS", "120")), 30), 3600)
LOCK = threading.RLock()
PROCESSES: dict[str, dict] = {}
TOKEN_PATTERN = re.compile(r"^[A-Za-z0-9_-]{20,4096}$")
FILE_PATTERN = re.compile(r"^(index\.m3u8|seg_\d{6,}\.ts)$")
DEFAULT_PORT = int(os.getenv("STREAM_GATEWAY_PORT", "8890"))


def load_key() -> bytes:
    value = os.getenv("STREAM_GATEWAY_KEY", "")
    try:
        key = base64.b64decode(value, validate=True)
    except Exception as exc:
        raise RuntimeError("STREAM_GATEWAY_KEY must be base64 of 32 bytes") from exc
    if len(key) != 32:
        raise RuntimeError("STREAM_GATEWAY_KEY must be base64 of 32 bytes")
    return key


KEY = load_key()


def decode_token(token: str) -> dict:
    if not TOKEN_PATTERN.fullmatch(token):
        raise ValueError("invalid token")
    try:
        encrypted = base64.urlsafe_b64decode(token + "=" * (-len(token) % 4))
        config = json.loads(AESGCM(KEY).decrypt(
            encrypted[:12], encrypted[12:], b"tilecast-stream-v1"
        ))
    except Exception as exc:
        raise ValueError("invalid stream token") from exc
    source = config.get("sourceUrl", "")
    if not isinstance(source, str) or not (1 <= len(source) <= 2500):
        raise ValueError("invalid stream source")
    address = urlsplit(source)
    if address.scheme.lower() not in ("rtsp", "rtmp", "rtmps") or not address.hostname:
        raise ValueError("invalid stream protocol")
    host = address.hostname.lower().rstrip(".")
    # Strict explicit allowlist prevents arbitrary server-side fetches. Prefer
    # literal camera / encoder IPs to DNS hostnames to avoid DNS rebinding.
    if not HOSTS or host not in HOSTS:
        raise ValueError("stream host is not in STREAM_ALLOWED_HOSTS")
    # Reject unrecognized or out-of-range settings even for an authenticated token.
    integers = {"videoBitrateKbps": (300, 12000), "volume": (0, 100),
                "reconnectSeconds": (2, 60), "segmentSeconds": (1, 6),
                "playlistSegments": (3, 12)}
    for name, (minimum, maximum) in integers.items():
        if type(config.get(name)) is not int or not minimum <= config[name] <= maximum:
            raise ValueError("invalid " + name)
    if config.get("rtspTransport") not in ("tcp", "udp"):
        raise ValueError("invalid RTSP transport")
    if config.get("videoCodec") not in ("copy", "transcode"):
        raise ValueError("invalid codec")
    if config.get("fit") not in ("contain", "cover", "fill"):
        raise ValueError("invalid fit")
    for name in ("audioEnabled", "muted", "showErrors"):
        if type(config.get(name)) is not bool:
            raise ValueError("invalid " + name)
    color = config.get("backgroundColor", "")
    if not isinstance(color, str) or not re.fullmatch(r"#[0-9a-fA-F]{6}", color):
        raise ValueError("invalid backgroundColor")
    return config


def key_for(token: str) -> str:
    return hashlib.sha256(token.encode("ascii")).hexdigest()[:40]


def ffmpeg_command(config: dict, directory: Path) -> list[str]:
    args = ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
            "-fflags", "+genpts", "-flags", "+low_delay"]
    if config["sourceUrl"].startswith("rtsp://"):
        args.extend(["-rtsp_transport", config["rtspTransport"]])
    args.extend(["-i", config["sourceUrl"], "-map", "0:v:0"])
    if config["audioEnabled"]:
        args.extend(["-map", "0:a:0?"])
    if config["videoCodec"] == "transcode":
        args.extend(["-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency",
                     "-pix_fmt", "yuv420p", "-b:v", str(config["videoBitrateKbps"]) + "k"])
    else:
        args.extend(["-c:v", "copy"])
    if config["audioEnabled"]:
        args.extend(["-c:a", "aac", "-b:a", "128k"])
    else:
        args.append("-an")
    args.extend(["-f", "hls", "-hls_time", str(config["segmentSeconds"]),
                 "-hls_list_size", str(config["playlistSegments"]),
                 "-hls_flags", "delete_segments+omit_endlist+independent_segments",
                 "-hls_segment_filename", str(directory / "seg_%06d.ts"),
                 str(directory / "index.m3u8")])
    return args


def terminate(info: dict) -> None:
    process = info["process"]
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=3)
    shutil.rmtree(info["directory"], ignore_errors=True)


def prune(now: float) -> None:
    for name, info in list(PROCESSES.items()):
        if now - info["last_seen"] >= IDLE_SECONDS:
            terminate(info)
            del PROCESSES[name]


def ensure_stream(token: str, config: dict) -> Path:
    name = key_for(token)
    now = time.monotonic()
    with LOCK:
        prune(now)
        previous = PROCESSES.get(name)
        if previous is not None:
            if previous["process"].poll() is None:
                previous["last_seen"] = now
                return previous["directory"]
            terminate(previous)
            del PROCESSES[name]
        if len(PROCESSES) >= MAX_STREAMS:
            raise RuntimeError("maximum concurrent streams reached")
        directory = ROOT / name
        shutil.rmtree(directory, ignore_errors=True)
        directory.mkdir(parents=True, exist_ok=True)
        # User values are individual subprocess arguments, never shell commands.
        process = subprocess.Popen(ffmpeg_command(config, directory),
                                   stdin=subprocess.DEVNULL,
                                   stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL,
                                   close_fds=True)
        PROCESSES[name] = {"process": process, "directory": directory,
                           "last_seen": now}
        return directory


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def send_bytes(self, status: int, content: bytes, mime: str,
                   cache: str = "no-store") -> None:
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Cache-Control", cache)
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Frame-Options", "SAMEORIGIN")
        self.end_headers()
        self.wfile.write(content)

    def error(self, code: int, message: str) -> None:
        self.send_bytes(code, message.encode("utf-8"), "text/plain; charset=utf-8")

    def do_GET(self) -> None:
        path = urlsplit(self.path).path
        if path == "/healthz":
            self.send_bytes(200, b"ok", "text/plain")
            return
        if path == "/hls.min.js":
            if not HLS_JS.is_file():
                self.error(503, "local HLS player unavailable")
                return
            self.send_bytes(200, HLS_JS.read_bytes(), "text/javascript; charset=utf-8",
                            "public, max-age=86400")
            return
        parts = path.strip("/").split("/")
        if len(parts) == 2 and parts[0] == "player":
            token = parts[1]
            try:
                config = decode_token(token)
            except ValueError as exc:
                self.error(403, str(exc))
                return
            settings = {k: config[k] for k in (
                "muted", "volume", "fit", "reconnectSeconds", "showErrors",
                "backgroundColor", "playlistSegments")}
            player_config = json.dumps(settings).replace("<", "\\u003c")
            player_token = json.dumps(token)
            page = HTML.replace("__CONFIG_JSON__", player_config).replace(
                "__TOKEN_JSON__", player_token)
            self.send_bytes(200, page.encode("utf-8"), "text/html; charset=utf-8")
            return
        if len(parts) == 3 and parts[0] == "hls":
            token, filename = parts[1:]
            if not FILE_PATTERN.fullmatch(filename):
                self.error(404, "not found")
                return
            try:
                config = decode_token(token)
                directory = ensure_stream(token, config)
            except ValueError as exc:
                self.error(403, str(exc))
                return
            except (RuntimeError, OSError) as exc:
                self.error(503, str(exc))
                return
            output = directory / filename
            if not output.is_file():
                self.error(503, "stream is starting")
                return
            try:
                content = output.read_bytes()
            except OSError:
                self.error(503, "segment unavailable")
                return
            mime = "application/vnd.apple.mpegurl" if filename.endswith(".m3u8") else "video/mp2t"
            self.send_bytes(200, content, mime)
            return
        self.error(404, "not found")

    def log_message(self, format: str, *args: object) -> None:
        # URLs contain bearer tokens, so HTTP request paths MUST NOT be logged.
        if self.path == "/healthz":
            return
        print("request", self.client_address[0], self.command,
              str(args[-1]) if args else "", flush=True)


def main() -> None:
    if not HOSTS:
        raise RuntimeError("STREAM_ALLOWED_HOSTS must explicitly list source hosts")
    ROOT.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer(("0.0.0.0", DEFAULT_PORT), Handler)
    server.daemon_threads = True
    print("Tilecast Stream Gateway listening on port", DEFAULT_PORT, flush=True)
    try:
        server.serve_forever(poll_interval=1)
    finally:
        server.server_close()
        with LOCK:
            for info in PROCESSES.values():
                terminate(info)
            PROCESSES.clear()


if __name__ == "__main__":
    main()
