#!/usr/bin/env python3
"""Loopback-only browser console for the MalekPlay Ultra Share desktop companion."""
from __future__ import annotations

import json
import mimetypes
import os
import secrets
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, unquote, urlparse

import ultra_share_pc as protocol


PAGE = r"""<!doctype html>
<html lang="ar" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="theme-color" content="#0d0d12"><title>MalekPlay Ultra Share</title>
<style>
:root{color-scheme:dark;font-family:Tahoma,Arial,sans-serif;background:#0d0d12;color:#f7f2ff}
*{box-sizing:border-box}body{margin:0;background:radial-gradient(ellipse at 85% 0,#55217a55,transparent 42%),#0d0d12;min-height:100vh}
main{max-width:900px;margin:auto;padding:28px 18px 60px}.brand{display:flex;align-items:center;gap:12px;margin-bottom:26px}
.logo{width:48px;height:48px;border-radius:16px;display:grid;place-items:center;background:linear-gradient(135deg,#ffe27a,#a855f7);color:#1b1026;font-weight:900;font-size:24px}
h1{font-size:25px;margin:0}.muted{color:#b9b1c8;font-size:13px;line-height:1.8}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}.card{background:#17131f;border:1px solid #a855f733;border-radius:20px;padding:20px;margin:14px 0}
h2{font-size:17px;margin:0 0 12px}.field{display:grid;gap:7px;margin:12px 0}.field label{font-size:12px;color:#c9bdd6}
input{width:100%;padding:12px;border-radius:12px;border:1px solid #443252;background:#0e0c14;color:white;font:inherit}
button{border:0;border-radius:12px;padding:12px 17px;background:linear-gradient(100deg,#a855f7,#7e36c9);color:white;font-weight:800;cursor:pointer}
button.secondary{background:#ffffff12;border:1px solid #ffffff22}.row{display:flex;gap:10px;align-items:center;flex-wrap:wrap}
.files{display:grid;gap:8px}.file{display:flex;justify-content:space-between;gap:12px;padding:12px;border-radius:12px;background:#ffffff08;border:1px solid #ffffff12;font-size:12px}
.status{padding:12px;border-radius:12px;background:#a855f71a;color:#e4caff;font-size:12px;line-height:1.7;white-space:pre-wrap}
.warning{color:#ffd58a}.progress{height:6px;background:#ffffff18;border-radius:8px;overflow:hidden;margin-top:10px}.progress i{display:block;height:100%;width:0;background:linear-gradient(90deg,#a855f7,#ffe27a)}
@media(max-width:680px){.grid{grid-template-columns:1fr}main{padding:20px 13px}}
</style></head><body><main>
<header class="brand"><div class="logo">M</div><div><h1>MalekPlay · Ultra Share</h1><div class="muted">مشاركة آمنة بين الكمبيوتر والهاتف من المتصفح</div></div></header>
<div class="status warning">هذه الصفحة تعمل على هذا الكمبيوتر فقط (127.0.0.1). لا تفتحها على شبكة عامة. النقل بين الكمبيوتر والهاتف يستخدم بروتوكول Ultra Share المشفر ورمز الاقتران.</div>
<div class="grid">
<section class="card"><h2>إرسال ملفات إلى الهاتف</h2>
<div class="field"><label>عنوان IPv4 الخاص بالهاتف</label><input id="host" placeholder="مثال: 192.168.1.25" inputmode="decimal"></div>
<div class="field"><label>رمز الاقتران الظاهر في MalekPlay</label><input id="pin" placeholder="9 أرقام" inputmode="numeric" maxlength="9"></div>
<div class="field"><label>اختر الملفات</label><input id="files" type="file" multiple></div>
<button id="send">إرسال الملفات المحددة</button><div class="progress"><i id="bar"></i></div>
<p id="sendStatus" class="muted">اختر الملفات وأدخل عنوان الهاتف ورمز الاقتران.</p>
</section>
<section class="card"><h2>الملفات المستلمة على الكمبيوتر</h2>
<p class="muted">تظهر هنا الملفات التي يرسلها هاتف MalekPlay إلى الكمبيوتر. اختر تنزيل لحفظ نسخة منها.</p>
<div class="row"><button class="secondary" id="refresh">تحديث القائمة</button><span id="count" class="muted"></span></div>
<div id="received" class="files" style="margin-top:13px"></div>
</section></div>
<section class="card"><h2>طريقة الاتصال</h2><p class="muted">1. صِل الهاتف والكمبيوتر بشبكة Wi‑Fi نفسها، أو صِل الكمبيوتر بنقطة اتصال الهاتف.<br>
2. في MalekPlay افتح UltraShare ثم ابدأ الاستقبال لعرض رمز الاقتران وعنوان الهاتف.<br>
3. للإرسال إلى الهاتف أدخل عنوانه ورمزه أعلاه. وللاستقبال على الكمبيوتر اكتب رمز الكمبيوتر الذي ظهر في الطرفية عند تشغيل هذه الأداة داخل MalekPlay.<br>
4. إذا لم يظهر الاتصال تلقائياً، اسمح للأداة عبر جدار حماية Windows للشبكات الخاصة فقط.</p></section>
</main><script>
const TOKEN="__TOKEN__";
const headers={"X-MalekPlay-Token":TOKEN};
const $=id=>document.getElementById(id);
async function api(path,options={}){const response=await fetch(path,{...options,headers:{...headers,...(options.headers||{})}});const body=await response.json().catch(()=>({error:"استجابة غير مفهومة"}));if(!response.ok)throw new Error(body.error||"فشل الطلب");return body}
async function refresh(){try{const data=await api("/api/files");$("count").textContent=data.files.length+" ملف";$("received").innerHTML="";for(const f of data.files){const row=document.createElement("div");row.className="file";const name=document.createElement("span");name.textContent=f.name+" · "+f.sizeLabel;const link=document.createElement("a");link.href="/download/"+encodeURIComponent(f.name)+"?token="+encodeURIComponent(TOKEN);link.textContent="تنزيل";link.style.color="#d9baff";link.style.textDecoration="none";row.append(name,link);$("received").append(row)}if(!data.files.length)$("received").textContent="لا توجد ملفات مستلمة بعد."}catch(e){$("received").textContent=e.message}}
$("refresh").onclick=refresh;
$("send").onclick=async()=>{const files=[...$("files").files],host=$("host").value.trim(),pin=$("pin").value.trim();if(!files.length||!host||!/^[0-9]{9}$/.test(pin)){ $("sendStatus").textContent="أدخل عنوان الهاتف ورمزاً من 9 أرقام واختر ملفاً واحداً على الأقل.";return}
$("send").disabled=true;$("bar").style.width="0%";let completed=0;
try{for(const file of files){$("sendStatus").textContent="جارٍ تجهيز "+file.name+" ("+file.size+" bytes)...";const upload=await api("/api/upload?name="+encodeURIComponent(file.name),{method:"POST",headers:{"Content-Type":"application/octet-stream"},body:file});$("sendStatus").textContent="جارٍ إرسال "+file.name+" عبر القناة المشفرة...";const result=await api("/api/send",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({host,pin,uploadId:upload.uploadId})});completed++;$("bar").style.width=(completed/files.length*100)+"%";$("sendStatus").textContent=result.message+" ("+completed+"/"+files.length+")"}}
catch(e){$("sendStatus").textContent="توقف الإرسال: "+e.message+" · يمكنك إعادة المحاولة؛ يدعم المستقبل استكمال الملفات عند توفر بيانات الاستئناف."}
finally{$("send").disabled=false}}
refresh();setInterval(refresh,5000);
</script></body></html>"""


def _size_label(size: int) -> str:
    value = float(size)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if value < 1024 or unit == "TB":
            return f"{value:.0f} {unit}" if unit == "B" else f"{value:.1f} {unit}"
        value /= 1024
    return f"{size} B"


def list_received_files(output: Path) -> list[dict[str, object]]:
    root = output.resolve()
    result: list[dict[str, object]] = []
    for item in sorted(output.iterdir(), key=lambda path: path.name.casefold()):
        try:
            resolved = item.resolve()
            if resolved.parent != root or not resolved.is_file() or item.name.startswith("."):
                continue
            stat = resolved.stat()
            result.append({"name": item.name, "size": stat.st_size, "sizeLabel": _size_label(stat.st_size)})
        except OSError:
            continue
    return result


def resolve_download_file(output: Path, raw_name: str) -> Path | None:
    try:
        name = protocol.safe_name(raw_name)
        root = output.resolve()
        candidate = (root / name).resolve()
        if candidate.parent != root or not candidate.is_file() or candidate.name.startswith("."):
            return None
        return candidate
    except (OSError, ValueError):
        return None


def run_web_share(
    output: Path,
    web_port: int,
    transfer_port: int,
    discovery_port: int,
    receive_secret: str,
    device_name: str,
) -> None:
    if not 1 <= web_port <= 65535 or not 1 <= transfer_port <= 65535 or not 1 <= discovery_port <= 65535:
        raise ValueError("Ports must be between 1 and 65535.")
    output = output.expanduser().resolve()
    output.mkdir(parents=True, exist_ok=True)
    pending_dir = output / ".malekplay-web-pending"
    pending_dir.mkdir(mode=0o700, exist_ok=True)
    token = secrets.token_urlsafe(32)
    uploads: dict[str, Path] = {}
    uploads_lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        server_version = "MalekPlayUltraShare/1"

        def log_message(self, _format: str, *_args) -> None:
            return

        def _json(self, status: int, payload: dict) -> None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def _authorized(self) -> bool:
            supplied = self.headers.get("X-MalekPlay-Token", "")
            if not supplied:
                supplied = parse_qs(urlparse(self.path).query).get("token", [""])[0]
            return secrets.compare_digest(supplied, token)

        def do_GET(self) -> None:
            path = urlparse(self.path).path
            if path == "/":
                body = PAGE.replace("__TOKEN__", token).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(body)
                return
            if not self._authorized():
                self._json(403, {"error": "رمز جلسة المتصفح غير صالح."})
                return
            if path == "/api/files":
                self._json(200, {"files": list_received_files(output)})
                return
            if path.startswith("/download/"):
                candidate = resolve_download_file(output, unquote(path[len("/download/"):]))
                if candidate is None:
                    self._json(404, {"error": "الملف غير موجود."})
                    return
                size = candidate.stat().st_size
                self.send_response(200)
                self.send_header("Content-Type", mimetypes.guess_type(candidate.name)[0] or "application/octet-stream")
                self.send_header("Content-Length", str(size))
                self.send_header("Content-Disposition", "attachment; filename*=UTF-8''" + quote(candidate.name))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                with candidate.open("rb") as stream:
                    while True:
                        chunk = stream.read(protocol.CHUNK_BYTES)
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                return
            self._json(404, {"error": "المسار غير موجود."})

        def do_POST(self) -> None:
            if not self._authorized():
                self._json(403, {"error": "رمز جلسة المتصفح غير صالح."})
                return
            path = urlparse(self.path).path
            try:
                if path == "/api/upload":
                    length = int(self.headers.get("Content-Length", "-1"))
                    if length < 0 or length > protocol.MAX_FILE_BYTES:
                        self._json(413, {"error": "حجم الملف خارج الحد المسموح."})
                        return
                    name = protocol.safe_name(parse_qs(urlparse(self.path).query).get("name", [""])[0])
                    upload_id = uuid.uuid4().hex
                    upload_dir = pending_dir / upload_id
                    upload_dir.mkdir(mode=0o700)
                    target = upload_dir / name
                    remaining = length
                    with target.open("xb") as stream:
                        while remaining:
                            chunk = self.rfile.read(min(protocol.CHUNK_BYTES, remaining))
                            if not chunk:
                                raise ConnectionError("انقطع رفع الملف من المتصفح.")
                            stream.write(chunk)
                            remaining -= len(chunk)
                    # Keep the validated filename in a sidecar-free in-memory record.
                    with uploads_lock:
                        uploads[upload_id] = target
                        uploads[upload_id + ":name"] = Path(name)
                    self._json(201, {"uploadId": upload_id})
                    return
                if path == "/api/send":
                    length = int(self.headers.get("Content-Length", "-1"))
                    if length <= 0 or length > 16_384:
                        self._json(413, {"error": "طلب الإرسال غير صالح."})
                        return
                    payload = json.loads(self.rfile.read(length).decode("utf-8"))
                    host = protocol.private_ipv4(str(payload.get("host", "")).strip())
                    secret = protocol.get_secret(str(payload.get("pin", "")))
                    upload_id = str(payload.get("uploadId", ""))
                    if len(upload_id) != 32 or any(ch not in "0123456789abcdef" for ch in upload_id):
                        self._json(400, {"error": "معرّف الملف المؤقت غير صالح."})
                        return
                    with uploads_lock:
                        source = uploads.pop(upload_id, None)
                        name = uploads.pop(upload_id + ":name", None)
                    if source is None or name is None or not source.is_file():
                        self._json(404, {"error": "انتهت صلاحية الملف المؤقت؛ أعد اختياره."})
                        return
                    try:
                        protocol.send_file(host, transfer_port, secret, source)
                    finally:
                        source.unlink(missing_ok=True)
                        source.parent.rmdir()
                    self._json(200, {"message": "تم إرسال الملف والتحقق من سلامته: " + name.name})
                    return
                self._json(404, {"error": "المسار غير موجود."})
            except (ValueError, PermissionError, OSError, ConnectionError, json.JSONDecodeError) as exc:
                self._json(400, {"error": str(exc) or "تعذر تنفيذ الطلب."})

    class LocalServer(ThreadingHTTPServer):
        daemon_threads = True
        allow_reuse_address = True

    server = LocalServer(("127.0.0.1", web_port), Handler)
    receiver_errors: list[str] = []

    def receive_loop() -> None:
        while True:
            try:
                protocol.receive_one(transfer_port, receive_secret, output)
            except (ConnectionError, OSError, ValueError, PermissionError) as exc:
                receiver_errors.append(str(exc))
                time.sleep(0.5)

    threading.Thread(target=receive_loop, name="malekplay-receiver", daemon=True).start()
    threading.Thread(
        target=protocol.discovery_responder,
        args=(discovery_port, transfer_port, device_name),
        name="malekplay-discovery",
        daemon=True,
    ).start()
    print(f"Browser console: http://127.0.0.1:{web_port}")
    print(f"Computer receive PIN: {receive_secret}")
    print(f"Received files folder: {output}")
    print("The browser console is bound to loopback only; it is not exposed to the LAN.")
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        print("\nStopping browser console.")
    finally:
        server.shutdown()
        server.server_close()
        for upload_dir in pending_dir.iterdir():
            if upload_dir.is_dir():
                for upload in upload_dir.iterdir():
                    upload.unlink(missing_ok=True)
                upload_dir.rmdir()
            else:
                upload_dir.unlink(missing_ok=True)
        pending_dir.rmdir() if not any(pending_dir.iterdir()) else None


if __name__ == "__main__":
    raise SystemExit("Run this module through: python ultra_share_pc.py web")
