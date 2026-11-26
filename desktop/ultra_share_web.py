#!/usr/bin/env python3
"""Loopback browser UI for the MalekPlay Ultra Share desktop companion.

The browser UI is intentionally bound to 127.0.0.1. LAN transfers still use
ultra_share_pc's authenticated, encrypted protocol; this HTTP server is only
a local control surface and never exposes the pairing PIN to the LAN.
"""
from __future__ import annotations

import argparse
import json
import mimetypes
import os
import re
import socket
import shutil
import tempfile
import threading
import time
import uuid
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote, unquote, urlsplit

from ultra_share_pc import (
    DEFAULT_PORT,
    DISCOVERY_PORT,
    MAX_FILE_BYTES,
    discover_devices,
    discovery_responder,
    get_secret,
    is_lan_ipv4,
    receive_one,
    safe_name,
    send_file,
)
from ultra_share_v2 import (
    DEFAULT_V2_PORT, receive_batch_v2, serve_control_once,
    connect_control_session, send_batch_v2,
)

MAX_HTTP_HEADER_NAME = 240
UPLOAD_CHUNK_BYTES = 1024 * 1024
INLINE_MEDIA_EXTENSIONS = {
    ".mp4", ".m4v", ".webm", ".mov", ".mkv", ".ogv",
    ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".flac",
    ".jpg", ".jpeg", ".png", ".webp", ".gif",
}


class PairingApprovalQueue:
    """Coordinates phone pairing approval between the browser UI and CLI."""

    def __init__(self, auto_approve: bool = False) -> None:
        self.auto_approve = auto_approve
        self._lock = threading.Lock()
        self._cond = threading.Condition(self._lock)
        self._pending: dict[str, dict] = {}
        self._decisions: dict[str, bool] = {}

    def request_approval(self, peer_name: str, peer_host: str, timeout: float = 90.0) -> bool:
        if self.auto_approve:
            return True
        request_id = str(uuid.uuid4())
        with self._cond:
            self._pending[request_id] = {
                "id": request_id,
                "peer_name": peer_name,
                "peer_host": peer_host,
                "created_at": time.time(),
            }
            deadline = time.monotonic() + timeout
            while request_id not in self._decisions:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    self._pending.pop(request_id, None)
                    return False
                self._cond.wait(timeout=remaining)
            self._pending.pop(request_id, None)
            return bool(self._decisions.pop(request_id, False))

    def list_pending(self) -> list[dict]:
        with self._lock:
            now = time.time()
            stale = [k for k, v in self._pending.items() if now - v["created_at"] > 120]
            for k in stale:
                self._pending.pop(k, None)
            return list(self._pending.values())

    def resolve(self, request_id: str, approved: bool) -> bool:
        with self._cond:
            if request_id not in self._pending:
                return False
            self._decisions[request_id] = bool(approved)
            self._cond.notify_all()
            return True


def detect_lan_ipv4s() -> list[str]:
    ips: list[str] = []
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("192.0.2.1", 80))
            local = probe.getsockname()[0]
            if is_lan_ipv4(local) and local not in ips:
                ips.append(local)
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if is_lan_ipv4(ip) and ip not in ips:
                ips.append(ip)
    except OSError:
        pass
    return ips


def is_local_host_header(value: str, expected_port: int) -> bool:
    """Reject DNS-rebinding Host headers; this UI must remain loopback-only."""
    try:
        parsed = urlsplit("//" + value)
        return (
            parsed.hostname in {"127.0.0.1", "localhost"}
            and parsed.port == expected_port
            and parsed.username is None
            and parsed.password is None
        )
    except ValueError:
        return False


def validate_upload(host: str, pin: str, filename: str, size: int) -> tuple[str, str, str]:
    host = host.strip()
    if not is_lan_ipv4(host):
        raise ValueError("أدخل عنوان IPv4 خاصاً للجهاز المستقبل.")
    if not (len(pin) == 9 and pin.isascii() and pin.isdigit()) and not (
        len(pin) == 64 and all(ch in "0123456789abcdefABCDEF" for ch in pin)
    ):
        raise ValueError("رمز الاقتران يجب أن يتكون من 9 أرقام.")
    if size < 0 or size > MAX_FILE_BYTES:
        raise ValueError("حجم الملف خارج الحد المدعوم.")
    clean_name = safe_name(filename)
    mime = mimetypes.guess_type(clean_name)[0] or "application/octet-stream"
    if Path(clean_name).suffix.lower() in {".apk", ".pdf"} or mime.lower() in {
        "application/pdf", "application/x-pdf",
        "application/vnd.android.package-archive",
    }:
        raise ValueError("لا يمكن مشاركة ملفات APK أو PDF.")
    if len(clean_name.encode("utf-8")) > MAX_HTTP_HEADER_NAME:
        raise ValueError("اسم الملف طويل جداً.")
    return host, pin, clean_name


def _parse_range_header(range_header: str, total_size: int) -> tuple[int, int] | None:
    if total_size <= 0 or not range_header.startswith("bytes="):
        return None
    spec = range_header[6:].split(",", 1)[0].strip()
    match = re.fullmatch(r"(\d*)-(\d*)", spec)
    if not match:
        return None
    start_s, end_s = match.group(1), match.group(2)
    if not start_s and not end_s:
        return None
    if not start_s:
        suffix = int(end_s)
        if suffix <= 0:
            return None
        start = max(0, total_size - suffix)
        end = total_size - 1
    else:
        start = int(start_s)
        end = int(end_s) if end_s else total_size - 1
    if start < 0 or start >= total_size or end < start:
        return None
    return start, min(end, total_size - 1)


def _media_kind(name: str) -> str:
    ext = Path(name).suffix.lower()
    if ext in {".mp4", ".m4v", ".webm", ".mov", ".mkv", ".ogv"}:
        return "video"
    if ext in {".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".flac"}:
        return "audio"
    if ext in {".jpg", ".jpeg", ".png", ".webp", ".gif"}:
        return "image"
    return "file"


def create_handler(output: Path, secret: str, transfer_port: int, v2_port: int,
                   control_port: int, web_port: int, discovery_port: int,
                   approval_queue: PairingApprovalQueue | None = None):
    output.mkdir(parents=True, exist_ok=True)
    output_root = output.resolve()
    staged_batches: dict[str, dict] = {}
    staged_lock = threading.Lock()
    approvals = approval_queue or PairingApprovalQueue(auto_approve=False)

    class Handler(BaseHTTPRequestHandler):
        server_version = "MalekPlayUltraShare/2"
        sys_version = ""

        def log_message(self, _format: str, *_args) -> None:
            # Do not log request headers: the pairing PIN is sent in a header.
            return

        def _json(self, status: int, payload: dict) -> None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(body)

        def _is_same_origin(self) -> bool:
            if not is_local_host_header(self.headers.get("Host", ""), web_port):
                return False
            origin = self.headers.get("Origin")
            if not origin:
                return True
            parsed = urlsplit(origin)
            return parsed.scheme == "http" and is_local_host_header(
                parsed.netloc, web_port
            )

        def do_GET(self) -> None:
            if not self._is_same_origin():
                self._json(403, {"ok": False, "error": "رفض طلب من مصدر غير محلي."})
                return
            parsed_url = urlsplit(self.path)
            route = parsed_url.path
            if route == "/":
                body = PAGE.encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(body)
                return
            if route == "/api/host-info":
                self._json(200, {
                    "ok": True,
                    "hostname": socket.gethostname(),
                    "lan_ips": detect_lan_ipv4s(),
                    "transfer_port": transfer_port,
                    "v2_port": v2_port,
                    "control_port": control_port,
                    "discovery_port": discovery_port,
                    "output_dir": str(output_root),
                    "auto_approve": approvals.auto_approve,
                    "secret": secret,
                })
                return
            if route == "/api/pending-approvals":
                self._json(200, {"ok": True, "requests": approvals.list_pending()})
                return
            if route == "/api/discover":
                try:
                    devices = discover_devices(timeout=2.5, port=discovery_port)
                    self._json(200, {"ok": True, "devices": [
                        {"host": host, "port": port, "name": name}
                        for host, port, name in devices
                    ]})
                except OSError as failure:
                    self._json(503, {"ok": False, "error": str(failure)[:300]})
                return
            if route == "/api/status":
                files = []
                for item in output_root.iterdir():
                    try:
                        resolved = item.resolve(strict=True)
                        if (
                            resolved.parent == output_root
                            and resolved.is_file()
                            and not item.name.startswith(".ultrashare-")
                            and not item.name.startswith(".")
                        ):
                            stat = resolved.stat()
                            kind = _media_kind(item.name)
                            encoded_name = quote(item.name, safe="")
                            files.append({
                                "name": item.name,
                                "size": stat.st_size,
                                "modified": int(stat.st_mtime),
                                "kind": kind,
                                "url": "/files/" + encoded_name,
                                "inline_url": "/files/" + encoded_name + "?inline=1",
                            })
                    except OSError:
                        continue
                files.sort(key=lambda item: item["modified"], reverse=True)
                self._json(200, {"ok": True, "files": files[:200]})
                return
            if route.startswith("/files/"):
                raw_name = unquote(route[len("/files/"):])
                inline_requested = "inline=1" in parsed_url.query
                try:
                    name = safe_name(raw_name)
                    if name != raw_name:
                        raise ValueError("invalid path")
                    candidate = (output_root / name).resolve(strict=True)
                    if candidate.parent != output_root or not candidate.is_file():
                        raise ValueError("invalid path")
                    size = candidate.stat().st_size
                    mime = mimetypes.guess_type(name)[0] or "application/octet-stream"
                    allow_inline = inline_requested and Path(name).suffix.lower() in INLINE_MEDIA_EXTENSIONS
                    range_hdr = self.headers.get("Range")
                    byte_range = _parse_range_header(range_hdr, size) if range_hdr else None
                    if range_hdr and byte_range is None:
                        self.send_response(416)
                        self.send_header("Content-Range", f"bytes */{size}")
                        self.end_headers()
                        return
                    if byte_range is not None:
                        start, end = byte_range
                        chunk_len = end - start + 1
                        self.send_response(206)
                        self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
                        self.send_header("Content-Length", str(chunk_len))
                    else:
                        start, end = 0, max(0, size - 1)
                        chunk_len = size
                        self.send_response(200)
                        self.send_header("Content-Length", str(size))
                    self.send_header("Content-Type", mime)
                    self.send_header("Accept-Ranges", "bytes")
                    disposition = "inline" if allow_inline else "attachment"
                    self.send_header(
                        "Content-Disposition",
                        f"{disposition}; filename=\"download\"; filename*=UTF-8''" + quote(name),
                    )
                    self.send_header("X-Content-Type-Options", "nosniff")
                    self.end_headers()
                    if size == 0:
                        return
                    with candidate.open("rb") as stream:
                        stream.seek(start)
                        remaining = chunk_len
                        while remaining > 0:
                            block = stream.read(min(UPLOAD_CHUNK_BYTES, remaining))
                            if not block:
                                break
                            self.wfile.write(block)
                            remaining -= len(block)
                except (OSError, ValueError):
                    self._json(404, {"ok": False, "error": "الملف غير موجود."})
                return
            self._json(404, {"ok": False, "error": "المسار غير موجود."})

        def do_POST(self) -> None:
            if not self._is_same_origin():
                self._json(403, {"ok": False, "error": "الطلب غير مسموح."})
                return
            if self.path == "/api/approve":
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                    if not 1 <= length <= 2048:
                        raise ValueError("بيانات الطلب غير صالحة.")
                    payload = json.loads(self.rfile.read(length).decode("utf-8"))
                    req_id = str(payload.get("id", "")).strip()
                    approved = bool(payload.get("approved", False))
                    if not req_id:
                        raise ValueError("معرّف الطلب غير موجود.")
                    resolved = approvals.resolve(req_id, approved)
                    if not resolved:
                        self._json(404, {"ok": False, "error": "انتهى الطلب أو تم حسمه مسبقاً."})
                        return
                    self._json(200, {"ok": True, "approved": approved})
                except (ValueError, TypeError, UnicodeDecodeError, json.JSONDecodeError) as failure:
                    self._json(400, {"ok": False, "error": str(failure)[:200]})
                return
            if self.path != "/api/send-v2-batch":
                self._json(403, {"ok": False, "error": "الطلب غير مسموح."})
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 1 <= length <= 4096:
                    raise ValueError("بيانات الدفعة غير صالحة.")
                payload = json.loads(self.rfile.read(length).decode("utf-8"))
                batch_id = str(uuid.UUID(str(payload.get("batch_id", ""))))
            except (ValueError, TypeError, AttributeError, UnicodeDecodeError, json.JSONDecodeError) as failure:
                self._json(400, {"ok": False, "error": str(failure)[:300] or "معرّف الدفعة غير صالح."})
                return

            with staged_lock:
                batch = staged_batches.pop(batch_id, None)
            if batch is None:
                self._json(404, {"ok": False, "error": "انتهت جلسة تجهيز الملفات أو لم تعد موجودة. أعد اختيار الملفات."})
                return
            try:
                paths = batch["paths"]
                if not paths:
                    raise ValueError("الدفعة لا تحتوي على ملفات.")
                with connect_control_session(batch["host"], control_port, batch["pin"]) as control:
                    results = send_batch_v2(batch["host"], v2_port, batch["pin"], paths)
                    if control.error:
                        raise ConnectionError(f"فُقدت قناة التحكم: {control.error}")
                if len(results) != len(paths) or any(item.get("status") != 2 for item in results):
                    raise OSError("لم يؤكد المستقبل اكتمال جميع ملفات الدفعة والتحقق منها.")
                self._json(200, {
                    "ok": True,
                    "message": f"تم إرسال الدفعة كاملة في جلسة V2 واحدة والتحقق من {len(results)} ملفاً.",
                    "count": len(results),
                    "names": [path.name for path in paths],
                })
            except Exception as failure:
                self._json(502, {"ok": False, "error": str(failure)[:500] or "فشل إرسال الدفعة."})
            finally:
                shutil.rmtree(batch["directory"], ignore_errors=True)

        def do_PUT(self) -> None:
            if self.path not in {"/api/send", "/api/send-v2", "/api/stage-v2"} or not self._is_same_origin():
                self._json(403, {"ok": False, "error": "الطلب غير مسموح."})
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                host, pin, filename = validate_upload(
                    self.headers.get("X-Ultra-Host", ""),
                    self.headers.get("X-Ultra-PIN", ""),
                    unquote(self.headers.get("X-Ultra-Filename", "")),
                    length,
                )
                if length == 0:
                    raise ValueError("الملف فارغ.")
            except (ValueError, TypeError) as failure:
                self._json(400, {"ok": False, "error": str(failure)})
                return

            staging_dir: Path | None = None
            try:
                staging_dir = Path(tempfile.mkdtemp(prefix=".ultrashare-web-", dir=output_root))
                source = staging_dir / filename
                written = 0
                with source.open("xb") as stream:
                    while written < length:
                        block = self.rfile.read(min(UPLOAD_CHUNK_BYTES, length - written))
                        if not block:
                            raise ConnectionError("انقطع رفع الملف من المتصفح.")
                        stream.write(block)
                        written += len(block)
                    stream.flush()
                    os.fsync(stream.fileno())
                if self.path == "/api/stage-v2":
                    try:
                        batch_id = str(uuid.UUID(self.headers.get("X-Ultra-Batch", "")))
                    except (ValueError, TypeError) as failure:
                        raise ValueError("معرّف دفعة المتصفح غير صالح.") from failure
                    now = time.time()
                    with staged_lock:
                        expired = [
                            key for key, item in staged_batches.items()
                            if now - item["created_at"] > 3600
                        ]
                        for key in expired:
                            old = staged_batches.pop(key, None)
                            if old:
                                shutil.rmtree(old["directory"], ignore_errors=True)
                        batch = staged_batches.get(batch_id)
                        if batch is None:
                            directory = Path(tempfile.mkdtemp(prefix=".ultrashare-web-batch-", dir=output_root))
                            batch = {
                                "host": host, "pin": pin, "directory": directory,
                                "paths": [], "created_at": now,
                            }
                            staged_batches[batch_id] = batch
                        elif batch["host"] != host or batch["pin"] != pin:
                            raise PermissionError("بيانات الهاتف أو رمز الاقتران تغيرت أثناء تجهيز الدفعة.")
                        if len(batch["paths"]) >= 512:
                            raise ValueError("الحد الأقصى للدفعة هو 512 ملفاً.")
                        item_dir = batch["directory"] / f"item-{len(batch['paths']):04d}"
                        item_dir.mkdir(parents=True, exist_ok=False)
                        destination = item_dir / filename
                        os.replace(source, destination)
                        batch["paths"].append(destination)
                        batch["created_at"] = now
                    self._json(200, {
                        "ok": True, "staged": len(batch["paths"]),
                        "message": f"تم تجهيز {len(batch['paths'])} ملفاً في الدفعة."
                    })
                elif self.path == "/api/send-v2":
                    with connect_control_session(host, control_port, pin) as control:
                        results = send_batch_v2(host, v2_port, pin, [source])
                        if control.error:
                            raise ConnectionError(f"فُقدت قناة التحكم: {control.error}")
                    if not results or results[0]["status"] != 2:
                        raise OSError("لم يؤكد المستقبل اكتمال الملف والتحقق منه.")
                    protocol_message = "تم إرسال الملف عبر V2 والتحقق من بصمته."
                else:
                    send_file(host, transfer_port, pin, source)
                    protocol_message = "تم إرسال الملف عبر V1 والتحقق من استلامه."
                self._json(200, {
                    "ok": True,
                    "message": protocol_message,
                    "name": filename,
                })
            except Exception as failure:
                self._json(502, {
                    "ok": False,
                    "error": str(failure)[:500] or "فشل الإرسال إلى الهاتف.",
                })
            finally:
                if staging_dir is not None:
                    shutil.rmtree(staging_dir, ignore_errors=True)

    return Handler


PAGE = r"""<!doctype html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>MalekPlay • مركز الكمبيوتر والبث</title>
<style>
:root{color-scheme:dark;--bg:#09070f;--panel:#181224;--line:#36264b;--purple:#a855f7;--text:#f7f0ff;--muted:#b8a9c8;--good:#4ade80;--bad:#fb7185}
*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 80% 0,#38204e 0,transparent 40%),var(--bg);color:var(--text);font:15px system-ui,sans-serif}
main{max-width:1040px;margin:auto;padding:24px 16px 54px}
.brand{display:flex;align-items:center;justify-content:space-between;gap:14px;flex-wrap:wrap;margin-bottom:20px}
.brandLeft{display:flex;align-items:center;gap:13px}
.logo{width:48px;height:48px;border-radius:16px;background:linear-gradient(145deg,#c084fc,#6d28d9);display:grid;place-items:center;font-weight:900;font-size:22px;box-shadow:0 8px 28px #a855f755}
h1{font-size:22px;margin:0}.sub{color:var(--muted);font-size:13px;margin-top:3px}
.hostPill{background:#181224;border:1px solid var(--line);border-radius:14px;padding:8px 13px;font-size:12px;color:var(--muted);line-height:1.6}
.hostPill strong{color:var(--text);font-family:ui-monospace,monospace}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}
.panel{background:#181224ef;border:1px solid var(--line);border-radius:22px;padding:20px;box-shadow:0 14px 40px #0005}
.panel h2{font-size:17px;margin:0 0 12px}
label{display:block;color:var(--muted);font-size:13px;margin:10px 0 5px}
input,select{width:100%;background:#0e0b16;color:var(--text);border:1px solid #4a3560;border-radius:12px;padding:10px 12px;font:inherit;min-width:0}
.dropzone{border:1.5px dashed #7c3aed;border-radius:14px;padding:16px;text-align:center;background:#110c1c;margin-top:10px;cursor:pointer;transition:.15s border-color,.15s background}
.dropzone.drag{border-color:#c084fc;background:#221638}
.dropzone input[type=file]{margin-top:8px;border:0;background:transparent;padding:0}
.row{display:flex;gap:8px;flex-wrap:wrap;margin-top:12px}
button,.download,.ghost{border:0;border-radius:12px;padding:10px 14px;background:linear-gradient(100deg,#a855f7,#7c3aed);color:white;font-weight:700;cursor:pointer;text-decoration:none;display:inline-flex;align-items:center;gap:6px;font-size:13px}
.ghost{background:#251b36;border:1px solid #4a3560;color:#e9d5ff}
button:disabled{opacity:.55;cursor:wait}
.progressWrap{margin-top:10px;display:none}.progressBar{height:8px;background:#100c18;border-radius:999px;overflow:hidden;border:1px solid #3c2a54}
.progressFill{height:100%;width:0%;background:linear-gradient(90deg,#a855f7,#4ade80);transition:width .12s linear}
.note{color:var(--muted);font-size:12px;line-height:1.65;margin-top:10px}
.status{margin-top:10px;min-height:22px;font-size:13px}.ok{color:var(--good)}.err{color:var(--bad)}
.file{padding:11px 0;border-bottom:1px solid #322248;display:flex;justify-content:space-between;align-items:center;gap:10px}
.file:last-child{border:0}.file strong{display:block;overflow-wrap:anywhere;font-size:14px}.file small{color:var(--muted)}
.fileActions{display:flex;gap:6px;flex-shrink:0}
.download{padding:7px 11px;font-size:12px}
.empty{color:var(--muted);font-size:13px;padding:14px 0}
.approvalBanner{background:linear-gradient(90deg,#2a1644,#1b142c);border:1px solid #a855f7;border-radius:16px;padding:12px 14px;margin-bottom:14px;display:none;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap}
.previewPanel{margin-top:16px;display:none}
.previewBox{background:#000;border-radius:14px;overflow:hidden;margin-top:10px}
.previewBox video,.previewBox img{width:100%;max-height:420px;display:block;object-fit:contain}
.previewBox audio{width:100%;padding:14px}
@media(max-width:740px){.grid{grid-template-columns:1fr}}
</style></head>
<body><main>
<header class="brand">
  <div class="brandLeft"><div class="logo">M</div><div><h1>MalekPlay • مركز الكمبيوتر والبث</h1><div class="sub">نقل مشفّر فائق السرعة + معاينة وسائط مباشرة + موافقة فورية</div></div></div>
  <div id="hostInfo" class="hostPill">جارٍ قراءة عنوان الكمبيوتر...</div>
</header>

<div id="approvalBanner" class="approvalBanner"></div>

<div class="grid">
<section class="panel">
  <h2>إرسال إلى الهاتف (Ultra Share)</h2>
  <div class="row" style="margin-top:0">
    <button id="discover" type="button" class="ghost">بحث عن الهواتف على الشبكة</button>
    <select id="devicePicker" style="flex:1;display:none"></select>
  </div>
  <label for="host">عنوان IP للهاتف المستقبل</label>
  <input id="host" inputmode="decimal" placeholder="مثال: 192.168.1.25">
  <label for="pin">رمز الاقتران (9 أرقام الظاهرة في الهاتف)</label>
  <input id="pin" inputmode="numeric" maxlength="9" autocomplete="off" placeholder="9 أرقام">
  <div id="dropzone" class="dropzone">
    <div>اسحب الملفات أو الفيديوهات وأفلتها هنا أو اخترها من الكمبيوتر</div>
    <input id="file" type="file" multiple>
  </div>
  <div class="row">
    <button id="sendV2" type="button">إرسال V2 المشفّر (دفعة واحدة)</button>
    <button id="send" type="button" class="ghost">إرسال V1</button>
  </div>
  <div id="progressWrap" class="progressWrap">
    <div class="progressBar"><div id="progressFill" class="progressFill"></div></div>
    <small id="progressText" class="note"></small>
  </div>
  <div id="sendStatus" class="status" aria-live="polite"></div>
  <p class="note">يدعم V2 إرسال حتى 512 ملفاً في جلسة مشفّرة واحدة مع التحقق التلقائي من البصمة SHA-256.</p>
</section>

<section class="panel">
  <h2>الملفات المستلمة ومشغل المعاينة</h2>
  <div id="files" class="empty">جارٍ تحميل الملفات...</div>
  <div class="row"><button id="refresh" type="button" class="ghost">تحديث القائمة</button></div>
  <div id="previewPanel" class="previewPanel">
    <div style="display:flex;justify-content:space-between;align-items:center;gap:8px">
      <strong id="previewTitle"></strong>
      <button id="closePreview" type="button" class="ghost" style="padding:5px 10px">إغلاق</button>
    </div>
    <div id="previewBox" class="previewBox"></div>
  </div>
  <p class="note">لمشاهدة البث الحي من شاشة الهاتف دون نقل الملفات: فعّل «البث المباشر للكمبيوتر» من الهاتف ثم افتح الرابط الظاهر عليه.</p>
</section>
</div>
</main>
<script>
const $=id=>document.getElementById(id);
function showStatus(message,kind){$('sendStatus').textContent=message;$('sendStatus').className='status '+(kind||'')}
function bytes(n){if(n<1024)return n+' B';const u=['KB','MB','GB','TB'];let v=n/1024,i=0;while(v>=1024&&i<u.length-1){v/=1024;i++}return v.toFixed(1)+' '+u[i]}

async function loadHostInfo(){
  try{
    const r=await fetch('/api/host-info',{cache:'no-store'});const d=await r.json();
    if(!d.ok)return;
    const ipList=(d.lan_ips&&d.lan_ips.length)?d.lan_ips.join(' / '):'غير متصل بـ LAN';
    $('hostInfo').innerHTML='<div>IP الكمبيوتر: <strong>'+ipList+'</strong></div><div>رمز الكمبيوتر: <strong>'+d.secret+'</strong> • V2:'+d.v2_port+'</div>';
  }catch(_){}
}

async function pollApprovals(){
  try{
    const r=await fetch('/api/pending-approvals',{cache:'no-store'});const d=await r.json();
    const banner=$('approvalBanner');banner.replaceChildren();
    if(!d.ok||!d.requests||!d.requests.length){banner.style.display='none';return}
    banner.style.display='flex';
    for(const req of d.requests){
      const wrap=document.createElement('div');
      wrap.style.cssText='display:flex;align-items:center;justify-content:space-between;gap:10px;width:100%;flex-wrap:wrap';
      const txt=document.createElement('div');
      txt.innerHTML='<strong>طلب اتصال من الهاتف:</strong> '+req.peer_name+' ('+req.peer_host+')';
      const btns=document.createElement('div');btns.className='row';btns.style.marginTop='0';
      const yes=document.createElement('button');yes.textContent='قبول الهاتف';
      yes.onclick=()=>resolveApproval(req.id,true);
      const no=document.createElement('button');no.className='ghost';no.textContent='رفض';
      no.onclick=()=>resolveApproval(req.id,false);
      btns.append(yes,no);wrap.append(txt,btns);banner.append(wrap);
    }
  }catch(_){}
}

async function resolveApproval(id,approved){
  await fetch('/api/approve',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id,approved})});
  pollApprovals();
}

async function discoverPhone(){
  const button=$('discover');button.disabled=true;showStatus('جارٍ البحث عن أجهزة MalekPlay على الشبكة المحلية...');
  try{
    const r=await fetch('/api/discover',{cache:'no-store'});const data=await r.json();
    if(!data.ok)throw new Error(data.error||'تعذر البحث');
    if(!data.devices.length){showStatus('لم يتم العثور على هاتف. تأكد من فتح غرفة الاستقبال على MalekPlay.','err');$('devicePicker').style.display='none';return}
    const picker=$('devicePicker');picker.replaceChildren();
    for(const dev of data.devices){
      const opt=document.createElement('option');opt.value=dev.host;opt.textContent=dev.name+' • '+dev.host;picker.append(opt);
    }
    picker.style.display=data.devices.length>1?'block':'none';
    picker.onchange=()=>{$('host').value=picker.value};
    $('host').value=data.devices[0].host;
    showStatus('تم العثور على '+data.devices[0].name+' • '+data.devices[0].host,'ok');
  }catch(e){showStatus(e.message||'تعذر البحث عن الهاتف.','err')}finally{button.disabled=false}
}

function openPreview(file){
  const panel=$('previewPanel'),box=$('previewBox');
  $('previewTitle').textContent=file.name;
  box.replaceChildren();
  if(file.kind==='video'){
    const v=document.createElement('video');v.controls=true;v.autoplay=true;v.src=file.inline_url;box.append(v);
  }else if(file.kind==='audio'){
    const a=document.createElement('audio');a.controls=true;a.autoplay=true;a.src=file.inline_url;box.append(a);
  }else if(file.kind==='image'){
    const img=document.createElement('img');img.src=file.inline_url;img.alt=file.name;box.append(img);
  }
  panel.style.display='block';
}
$('closePreview').onclick=()=>{$('previewPanel').style.display='none';$('previewBox').replaceChildren()};

async function refreshFiles(){
  try{
    const r=await fetch('/api/status',{cache:'no-store'});const data=await r.json();
    const root=$('files');root.replaceChildren();
    if(!data.files.length){root.className='empty';root.textContent='لا توجد ملفات مستلمة بعد.';return}
    root.className='';
    for(const f of data.files){
      const row=document.createElement('div');row.className='file';
      const info=document.createElement('div');
      const name=document.createElement('strong');name.textContent=f.name;
      const size=document.createElement('small');size.textContent=bytes(f.size);
      info.append(name,size);
      const actions=document.createElement('div');actions.className='fileActions';
      if(f.kind==='video'||f.kind==='audio'||f.kind==='image'){
        const play=document.createElement('button');play.type='button';play.className='ghost';play.style.padding='7px 10px';play.textContent='تشغيل';
        play.onclick=()=>openPreview(f);actions.append(play);
      }
      const link=document.createElement('a');link.className='download';link.href=f.url;link.textContent='تنزيل';
      actions.append(link);row.append(info,actions);root.append(row);
    }
  }catch(_){$('files').textContent='تعذر تحميل قائمة الملفات.'}
}

function putWithProgress(endpoint,headers,file,label){
  return new Promise((resolve,reject)=>{
    const xhr=new XMLHttpRequest();xhr.open('PUT',endpoint,true);
    for(const [k,v] of Object.entries(headers))xhr.setRequestHeader(k,v);
    const start=performance.now();
    $('progressWrap').style.display='block';
    xhr.upload.onprogress=ev=>{
      if(!ev.lengthComputable)return;
      const pct=Math.min(100,Math.round((ev.loaded/ev.total)*100));
      const elapsed=Math.max(0.1,(performance.now()-start)/1000);
      const mbps=(ev.loaded/(1024*1024))/elapsed;
      $('progressFill').style.width=pct+'%';
      $('progressText').textContent=label+' • '+pct+'% ('+mbps.toFixed(1)+' MB/s)';
    };
    xhr.onload=()=>{
      try{
        const data=JSON.parse(xhr.responseText||'{}');
        if(xhr.status>=200&&xhr.status<300&&data.ok)resolve(data);
        else reject(new Error(data.error||'فشل الطلب ('+xhr.status+')'));
      }catch(_){reject(new Error('استجابة غير متوقعة من الخادم'))}
    };
    xhr.onerror=()=>reject(new Error('تعذر الاتصال بالخادم المحلي'));
    xhr.send(file);
  });
}

async function sendSelected(endpoint,buttonId){
  const files=[...$('file').files],host=$('host').value.trim(),pin=$('pin').value.trim();
  if(!files.length||!host||!pin){showStatus('أدخل عنوان الهاتف ورمز الاقتران واختر ملفاً واحداً على الأقل.','err');return}
  const button=$(buttonId);button.disabled=true;let sent=0;
  try{
    for(let index=0;index<files.length;index++){
      const file=files[index];
      showStatus('جارٍ إرسال '+file.name+' ('+(index+1)+'/'+files.length+')...');
      const result=await putWithProgress(endpoint,{'Content-Type':'application/octet-stream','X-Ultra-Host':host,'X-Ultra-PIN':pin,'X-Ultra-Filename':encodeURIComponent(file.name)},file,file.name);
      sent++;showStatus(result.message+' ('+sent+'/'+files.length+')','ok');
    }
  }catch(e){showStatus('اكتمل إرسال '+sent+' من '+files.length+' ملفات. '+(e.message||'حدث خطأ أثناء الإرسال.'),'err')}
  finally{button.disabled=false;setTimeout(()=>{$('progressWrap').style.display='none'},1500)}
}

async function sendV2Batch(){
  const files=[...$('file').files],host=$('host').value.trim(),pin=$('pin').value.trim();
  if(!files.length||!host||!pin){showStatus('أدخل عنوان الهاتف ورمز الاقتران واختر ملفاً واحداً على الأقل.','err');return}
  if(files.length>512){showStatus('الحد الأقصى للدفعة الواحدة هو 512 ملفاً.','err');return}
  const button=$('sendV2');button.disabled=true;const batchId=crypto.randomUUID();
  try{
    for(let index=0;index<files.length;index++){
      const file=files[index];
      showStatus('جارٍ تجهيز '+file.name+' ('+(index+1)+'/'+files.length+') للدفعة المشفّرة...');
      await putWithProgress('/api/stage-v2',{'Content-Type':'application/octet-stream','X-Ultra-Host':host,'X-Ultra-PIN':pin,'X-Ultra-Filename':encodeURIComponent(file.name),'X-Ultra-Batch':batchId},file,'تجهيز '+file.name);
    }
    showStatus('جارٍ إرسال '+files.length+' ملفاً إلى الهاتف ضمن جلسة V2 المشفّرة...');
    const response=await fetch('/api/send-v2-batch',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({batch_id:batchId})});
    const result=await response.json();
    if(!response.ok||!result.ok)throw new Error(result.error||'فشل إرسال الدفعة');
    showStatus(result.message,'ok');
  }catch(e){showStatus('لم تكتمل الدفعة. '+(e.message||'حدث خطأ أثناء الإرسال.'),'err')}
  finally{button.disabled=false;setTimeout(()=>{$('progressWrap').style.display='none'},1500)}
}

const dz=$('dropzone');
dz.addEventListener('dragover',e=>{e.preventDefault();dz.classList.add('drag')});
dz.addEventListener('dragleave',()=>dz.classList.remove('drag'));
dz.addEventListener('drop',e=>{
  e.preventDefault();dz.classList.remove('drag');
  if(e.dataTransfer&&e.dataTransfer.files&&e.dataTransfer.files.length){
    $('file').files=e.dataTransfer.files;
    showStatus('تم اختيار '+e.dataTransfer.files.length+' ملفاً جاهزاً للإرسال.');
  }
});

$('send').addEventListener('click',()=>sendSelected('/api/send','send'));
$('sendV2').addEventListener('click',sendV2Batch);
$('discover').addEventListener('click',discoverPhone);
$('refresh').addEventListener('click',refreshFiles);
loadHostInfo();refreshFiles();pollApprovals();
setInterval(refreshFiles,5000);setInterval(pollApprovals,1500);
</script></body></html>"""


def main() -> int:
    parser = argparse.ArgumentParser(description="MalekPlay Ultra Share browser companion")
    parser.add_argument("--secret", help="9-digit pairing PIN; prompted if omitted")
    parser.add_argument("--output", type=Path, default=Path("Received"))
    parser.add_argument("--transfer-port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--v2-port", type=int, default=DEFAULT_V2_PORT,
                        help="TCP port for authenticated V2 batch reception")
    parser.add_argument("--control-port", type=int, default=38744,
                        help="TCP port for the authenticated MPC2 control channel")
    parser.add_argument("--discovery-port", type=int, default=DISCOVERY_PORT)
    parser.add_argument("--web-port", type=int, default=8765)
    parser.add_argument("--auto-approve", action="store_true",
                        help="Automatically approve incoming phones authenticated with the pairing PIN")
    parser.add_argument("--open-browser", action="store_true",
                        help="Open the local web console automatically in the default browser")
    args = parser.parse_args()
    if not (1 <= args.transfer_port <= 65535 and 1 <= args.v2_port <= 65535
            and 1 <= args.control_port <= 65535 and 1 <= args.discovery_port <= 65535):
        parser.error("Transfer, V2 and discovery ports must be between 1 and 65535.")
    if args.v2_port == args.transfer_port:
        parser.error("V1 and V2 TCP ports must be different.")
    if args.control_port in {args.transfer_port, args.v2_port}:
        parser.error("The MPC2 control TCP port must be distinct from both data ports.")
    if not 1024 <= args.web_port <= 65535:
        parser.error("The browser UI port must be between 1024 and 65535.")
    secret = get_secret(args.secret)
    output = args.output.expanduser().resolve()
    output.mkdir(parents=True, exist_ok=True)
    stopped = threading.Event()
    approval_queue = PairingApprovalQueue(auto_approve=args.auto_approve)

    def receiver_loop() -> None:
        while not stopped.is_set():
            try:
                receive_one(args.transfer_port, secret, output)
            except (OSError, ValueError, PermissionError, ConnectionError) as failure:
                if not stopped.is_set():
                    print(f"Transfer interrupted or rejected: {failure}")
                    time.sleep(0.4)

    def v2_receiver_loop() -> None:
        while not stopped.is_set():
            try:
                outcomes = receive_batch_v2(args.v2_port, secret, output)
                completed = sum(item["status"] == 2 for item in outcomes)
                print(f"V2 batch received: {completed}/{len(outcomes)} files verified.")
            except (OSError, ValueError, PermissionError, ConnectionError) as failure:
                if not stopped.is_set():
                    print(f"V2 transfer interrupted or rejected: {failure}")
                    time.sleep(0.4)

    threading.Thread(target=receiver_loop, name="ultrashare-receiver-v1", daemon=True).start()

    def control_approval_loop() -> None:
        while not stopped.is_set():
            try:
                def approve(peer_name: str, peer_host: str) -> bool:
                    if approval_queue.auto_approve:
                        print(f"\nAuto-approved authenticated phone: {peer_name} ({peer_host})")
                        return True
                    print(f"\n{peer_name} ({peer_host}) requests Ultra Share access (approve in browser UI)...")
                    return approval_queue.request_approval(peer_name, peer_host)

                serve_control_once(args.control_port, secret, socket.gethostname(), approve)
            except (OSError, ValueError, PermissionError, ConnectionError) as failure:
                if not stopped.is_set():
                    print(f"Control request rejected or interrupted: {failure}")
                    time.sleep(0.4)

    threading.Thread(target=v2_receiver_loop, name="ultrashare-receiver-v2", daemon=True).start()
    threading.Thread(target=control_approval_loop, name="ultrashare-control-server", daemon=True).start()
    threading.Thread(
        target=discovery_responder,
        args=(args.discovery_port, args.transfer_port, socket.gethostname()),
        name="ultrashare-discovery",
        daemon=True,
    ).start()

    server = ThreadingHTTPServer(
        ("127.0.0.1", args.web_port),
        create_handler(
            output, secret, args.transfer_port, args.v2_port,
            args.control_port, args.web_port, args.discovery_port,
            approval_queue=approval_queue,
        ),
    )
    server.daemon_threads = True
    url = f"http://127.0.0.1:{args.web_port}"
    lan_ips = detect_lan_ipv4s()
    print(f"MalekPlay Ultra Share browser UI: {url}")
    if lan_ips:
        print(f"PC LAN IP(s): {', '.join(lan_ips)}")
    print(f"Receiving V1/V2 data on TCP {args.transfer_port}/{args.v2_port}; MPC2 control on TCP {args.control_port} into: {output}")
    print("Keep this terminal open. Press Ctrl+C to stop.")
    if args.open_browser:
        threading.Timer(0.4, lambda: webbrowser.open(url)).start()
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        stopped.set()
        server.shutdown()
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
