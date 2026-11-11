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
import socket
import shutil
import tempfile
import threading
import time
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

MAX_HTTP_HEADER_NAME = 240
UPLOAD_CHUNK_BYTES = 1024 * 1024


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
    if len(clean_name.encode("utf-8")) > MAX_HTTP_HEADER_NAME:
        raise ValueError("اسم الملف طويل جداً.")
    return host, pin, clean_name


def create_handler(output: Path, secret: str, transfer_port: int, web_port: int, discovery_port: int):
    output.mkdir(parents=True, exist_ok=True)
    output_root = output.resolve()

    class Handler(BaseHTTPRequestHandler):
        server_version = "MalekPlayUltraShare/1"
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
            if self.path == "/":
                body = PAGE.encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(body)
                return
            if self.path == "/api/discover":
                try:
                    devices = discover_devices(timeout=2.5, port=discovery_port)
                    self._json(200, {"ok": True, "devices": [
                        {"host": host, "port": port, "name": name}
                        for host, port, name in devices
                    ]})
                except OSError as failure:
                    self._json(503, {"ok": False, "error": str(failure)[:300]})
                return
            if self.path == "/api/status":
                files = []
                for item in output_root.iterdir():
                    try:
                        if item.is_file() and not item.name.startswith(".ultrashare-"):
                            stat = item.stat()
                            files.append({
                                "name": item.name,
                                "size": stat.st_size,
                                "modified": int(stat.st_mtime),
                                "url": "/files/" + quote(item.name, safe=""),
                            })
                    except OSError:
                        continue
                files.sort(key=lambda item: item["modified"], reverse=True)
                self._json(200, {"ok": True, "files": files[:200]})
                return
            if self.path.startswith("/files/"):
                raw_name = unquote(self.path[len("/files/"):].split("?", 1)[0])
                try:
                    name = safe_name(raw_name)
                    if name != raw_name:
                        raise ValueError("invalid path")
                    candidate = (output_root / name).resolve(strict=True)
                    if candidate.parent != output_root or not candidate.is_file():
                        raise ValueError("invalid path")
                    size = candidate.stat().st_size
                    self.send_response(200)
                    self.send_header(
                        "Content-Type",
                        mimetypes.guess_type(name)[0] or "application/octet-stream",
                    )
                    self.send_header("Content-Length", str(size))
                    self.send_header(
                        "Content-Disposition",
                        "attachment; filename=\"download\"; filename*=UTF-8''" + quote(name),
                    )
                    self.send_header("X-Content-Type-Options", "nosniff")
                    self.end_headers()
                    with candidate.open("rb") as stream:
                        while True:
                            block = stream.read(UPLOAD_CHUNK_BYTES)
                            if not block:
                                break
                            self.wfile.write(block)
                except (OSError, ValueError):
                    self._json(404, {"ok": False, "error": "الملف غير موجود."})
                return
            self._json(404, {"ok": False, "error": "المسار غير موجود."})

        def do_PUT(self) -> None:
            if self.path != "/api/send" or not self._is_same_origin():
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
                send_file(host, transfer_port, pin, source)
                self._json(200, {
                    "ok": True,
                    "message": "تم إرسال الملف والتحقق من استلامه.",
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
<title>MalekPlay • Ultra Share</title>
<style>
:root{color-scheme:dark;--bg:#0d0b13;--panel:#1b1425;--line:#3a2850;--purple:#a855f7;--text:#f7f0ff;--muted:#b8a9c8;--good:#55d6a2;--bad:#ff7188}
*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 80% 0,#38204e 0,transparent 38%),var(--bg);color:var(--text);font:16px system-ui,sans-serif}
main{max-width:900px;margin:auto;padding:28px 18px 50px}.brand{display:flex;align-items:center;gap:13px;margin-bottom:24px}.logo{width:48px;height:48px;border-radius:16px;background:linear-gradient(145deg,#c084fc,#6d28d9);display:grid;place-items:center;font-weight:900;font-size:22px;box-shadow:0 8px 28px #a855f755}
h1{font-size:23px;margin:0}.sub{color:var(--muted);font-size:13px;margin-top:3px}.grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}.panel{background:#1b1425eF;border:1px solid var(--line);border-radius:22px;padding:20px;box-shadow:0 14px 40px #0005}.panel h2{font-size:18px;margin:0 0 14px}
label{display:block;color:var(--muted);font-size:13px;margin:12px 0 6px}input{width:100%;background:#100d17;color:var(--text);border:1px solid #4a3560;border-radius:12px;padding:12px;font:inherit;min-width:0}input[type=file]{padding:10px}
button,.download{border:0;border-radius:12px;padding:12px 16px;background:linear-gradient(100deg,#a855f7,#7c3aed);color:white;font-weight:750;cursor:pointer;text-decoration:none;display:inline-block;margin-top:14px}button:disabled{opacity:.55;cursor:wait}
.note{color:var(--muted);font-size:12px;line-height:1.7}.status{margin-top:12px;min-height:24px;font-size:13px}.ok{color:var(--good)}.err{color:var(--bad)}.file{padding:12px 0;border-bottom:1px solid #3a2850;display:flex;justify-content:space-between;align-items:center;gap:12px}.file:last-child{border:0}.file strong{display:block;overflow-wrap:anywhere}.file small{color:var(--muted)}.download{margin:0;padding:8px 12px;font-size:12px;white-space:nowrap}.empty{color:var(--muted);font-size:13px;padding:14px 0}
@media(max-width:680px){.grid{grid-template-columns:1fr}main{padding:20px 13px}}
</style></head>
<body><main>
<header class="brand"><div class="logo">M</div><div><h1>MalekPlay Ultra Share</h1><div class="sub">مشاركة محلية مشفّرة بين الكمبيوتر والهاتف</div></div></header>
<div class="grid">
<section class="panel"><h2>إرسال ملف إلى الهاتف</h2>
<label for="host">عنوان IP للهاتف المستقبل</label><input id="host" inputmode="decimal" placeholder="مثال: 192.168.1.25">
<label for="pin">رمز الاقتران الظاهر في MalekPlay</label><input id="pin" inputmode="numeric" maxlength="9" autocomplete="off" placeholder="9 أرقام">
<button id="discover" type="button">البحث عن الهاتف على الشبكة</button>
<label for="file">اختر ملفاً من الكمبيوتر</label><input id="file" type="file">
<button id="send">إرسال إلى الهاتف</button><div id="sendStatus" class="status" aria-live="polite"></div>
<p class="note">تأكد أن الكمبيوتر والهاتف على شبكة Wi-Fi نفسها أو أن الكمبيوتر متصل بنقطة اتصال الهاتف. لا تغلق الصفحة أثناء النقل.</p>
</section>
<section class="panel"><h2>الملفات المستلمة على الكمبيوتر</h2><div id="files" class="empty">جارٍ تحميل الملفات...</div>
<p class="note">تظهر هنا الملفات التي أرسلها هاتف MalekPlay إلى مجلد الاستقبال المحدد عند تشغيل هذه الأداة.</p><button id="refresh" type="button">تحديث القائمة</button>
</section></div>
<p class="note" style="margin-top:18px">صفحة التحكم محلية على هذا الكمبيوتر فقط. النقل بين الأجهزة يستخدم بروتوكول Ultra Share المشفّر والتحقق من SHA-256، ولا يتم رفع ملفاتك إلى الإنترنت.</p>
</main>
<script>
const $=id=>document.getElementById(id);
function showStatus(message,kind){$('sendStatus').textContent=message;$('sendStatus').className='status '+(kind||'')}
function bytes(n){if(n<1024)return n+' B';const u=['KB','MB','GB','TB'];let v=n/1024,i=0;while(v>=1024&&i<u.length-1){v/=1024;i++}return v.toFixed(1)+' '+u[i]}
async function discoverPhone(){const button=$('discover');button.disabled=true;showStatus('جارٍ البحث عن أجهزة MalekPlay على الشبكة المحلية...');try{const r=await fetch('/api/discover',{cache:'no-store'});const data=await r.json();if(!data.ok)throw new Error(data.error||'تعذر البحث');if(!data.devices.length){showStatus('لم يتم العثور على هاتف. تأكد من تشغيل الاستقبال على MalekPlay.','err');return}const device=data.devices[0];$('host').value=device.host;showStatus('تم العثور على '+device.name+' • '+device.host,'ok')}catch(e){showStatus(e.message||'تعذر البحث عن الهاتف.','err')}finally{button.disabled=false}}
async function refreshFiles(){try{const r=await fetch('/api/status',{cache:'no-store'});const data=await r.json();const root=$('files');root.replaceChildren();if(!data.files.length){root.className='empty';root.textContent='لا توجد ملفات مستلمة بعد.';return}root.className='';for(const f of data.files){const row=document.createElement('div');row.className='file';const info=document.createElement('div');const name=document.createElement('strong');name.textContent=f.name;const size=document.createElement('small');size.textContent=bytes(f.size);info.append(name,size);const link=document.createElement('a');link.className='download';link.href=f.url;link.textContent='تنزيل';row.append(info,link);root.append(row)}}catch(e){$('files').textContent='تعذر تحميل قائمة الملفات.'}}
$('send').addEventListener('click',async()=>{const file=$('file').files[0],host=$('host').value.trim(),pin=$('pin').value.trim();if(!file||!host||!pin){showStatus('أدخل عنوان الهاتف ورمز الاقتران واختر ملفاً.','err');return}const button=$('send');button.disabled=true;showStatus('جارٍ رفع الملف إلى أداة المشاركة المحلية ثم إرساله...');try{const response=await fetch('/api/send',{method:'PUT',headers:{'Content-Type':'application/octet-stream','X-Ultra-Host':host,'X-Ultra-PIN':pin,'X-Ultra-Filename':encodeURIComponent(file.name)},body:file});const result=await response.json();if(!response.ok||!result.ok)throw new Error(result.error||'فشل الإرسال');showStatus(result.message+' ('+result.name+')','ok')}catch(e){showStatus(e.message||'حدث خطأ أثناء الإرسال.','err')}finally{button.disabled=false}});
$('discover').addEventListener('click',discoverPhone);$('refresh').addEventListener('click',refreshFiles);refreshFiles();setInterval(refreshFiles,5000);
</script></body></html>"""


def main() -> int:
    parser = argparse.ArgumentParser(description="MalekPlay Ultra Share browser companion")
    parser.add_argument("--secret", help="9-digit pairing PIN; prompted if omitted")
    parser.add_argument("--output", type=Path, default=Path("Received"))
    parser.add_argument("--transfer-port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--discovery-port", type=int, default=DISCOVERY_PORT)
    parser.add_argument("--web-port", type=int, default=8765)
    args = parser.parse_args()
    if not (1 <= args.transfer_port <= 65535 and 1 <= args.discovery_port <= 65535):
        parser.error("Transfer and discovery ports must be between 1 and 65535.")
    if not 1024 <= args.web_port <= 65535:
        parser.error("The browser UI port must be between 1024 and 65535.")
    secret = get_secret(args.secret)
    output = args.output.expanduser().resolve()
    output.mkdir(parents=True, exist_ok=True)
    stopped = threading.Event()

    def receiver_loop() -> None:
        while not stopped.is_set():
            try:
                receive_one(args.transfer_port, secret, output)
            except (OSError, ValueError, PermissionError, ConnectionError) as failure:
                if not stopped.is_set():
                    print(f"Transfer interrupted or rejected: {failure}")
                    time.sleep(0.4)

    threading.Thread(target=receiver_loop, name="ultrashare-receiver", daemon=True).start()
    threading.Thread(
        target=discovery_responder,
        args=(args.discovery_port, args.transfer_port, socket.gethostname()),
        name="ultrashare-discovery",
        daemon=True,
    ).start()

    server = ThreadingHTTPServer(
        ("127.0.0.1", args.web_port),
        create_handler(output, secret, args.transfer_port, args.web_port, args.discovery_port),
    )
    server.daemon_threads = True
    print(f"MalekPlay Ultra Share browser UI: http://127.0.0.1:{args.web_port}")
    print(f"Receiving Android files into: {output}")
    print("Keep this terminal open. Press Ctrl+C to stop.")
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
