"""Android-compatible Ultra Share V2 manifest, data transport and MPC2 control.

The V1 desktop transport remains available in ultra_share_pc.py for legacy
peers. V2 uses its own authenticated batch data session and the separately
approved MPC2 control channel.
"""
from __future__ import annotations

import hashlib
import struct
import uuid
from dataclasses import dataclass

MAGIC = 0x4D505532  # Android UltraShareBatchProtocol.MAGIC (MPU2)
VERSION = 2
MAX_FILES = 512
MAX_MANIFEST_BYTES = 512 * 1024
MAX_NAME_BYTES = 180
MAX_MIME_BYTES = 192
MAX_FILE_BYTES = 100 * 1024 * 1024 * 1024
DIGEST_BYTES = 32


@dataclass(frozen=True)
class FileEntry:
    file_id: str
    name: str
    mime_type: str
    size_bytes: int
    sha256: bytes


@dataclass(frozen=True)
class Manifest:
    batch_id: str
    entries: tuple[FileEntry, ...]

    @property
    def total_bytes(self) -> int:
        total = 0
        for entry in self.entries:
            if total > (2**63 - 1) - entry.size_bytes:
                raise ValueError("Batch size overflows signed 64-bit range.")
            total += entry.size_bytes
        return total


def _uuid_bytes(value: str) -> bytes:
    try:
        return uuid.UUID(value).bytes
    except (ValueError, AttributeError, TypeError) as exc:
        raise ValueError("Invalid UUID in Ultra Share V2 manifest.") from exc


def _safe_name(value: str) -> str:
    leaf = value.replace("\\", "/").rsplit("/", 1)[-1]
    cleaned = "".join(
        ch for ch in leaf
        if ord(ch) >= 32 and not 127 <= ord(ch) <= 159
        and ch not in ':*?"<>|'
        and __import__("unicodedata").category(ch) != "Cf"
    ).strip().strip(".")
    while cleaned and len(cleaned.encode("utf-8")) > MAX_NAME_BYTES:
        cleaned = cleaned[:-1]
    if not cleaned or cleaned in {".", ".."}:
        raise ValueError("Invalid shared filename.")
    return cleaned


def _valid_mime(value: str) -> bool:
    if not isinstance(value, str) or not 3 <= len(value) <= 160:
        return False
    token = r"[A-Za-z0-9!#$&^_.+-]+"
    import re
    return re.fullmatch(token + "/" + token, value) is not None


def validate_manifest(manifest: Manifest) -> Manifest:
    _uuid_bytes(manifest.batch_id)
    if not 1 <= len(manifest.entries) <= MAX_FILES:
        raise ValueError("Invalid V2 manifest entry count.")
    seen: set[str] = set()
    for entry in manifest.entries:
        _uuid_bytes(entry.file_id)
        if entry.file_id in seen:
            raise ValueError("Duplicate V2 file id.")
        seen.add(entry.file_id)
        if _safe_name(entry.name) != entry.name:
            raise ValueError("Filename is not sanitized.")
        if not 1 <= len(entry.name.encode("utf-8")) <= MAX_NAME_BYTES:
            raise ValueError("Filename exceeds V2 limit.")
        if not _valid_mime(entry.mime_type):
            raise ValueError("Invalid V2 MIME type.")
        if len(entry.mime_type.encode("utf-8")) > MAX_MIME_BYTES:
            raise ValueError("MIME type exceeds V2 limit.")
        if not isinstance(entry.size_bytes, int) or not 0 <= entry.size_bytes <= MAX_FILE_BYTES:
            raise ValueError("File size outside V2 limit.")
        if not isinstance(entry.sha256, bytes) or len(entry.sha256) != DIGEST_BYTES:
            raise ValueError("Invalid SHA-256 digest.")
    manifest.total_bytes
    return manifest


def _text(value: str, maximum: int) -> bytes:
    encoded = value.encode("utf-8")
    if not 1 <= len(encoded) <= maximum:
        raise ValueError("V2 text field outside configured limit.")
    return struct.pack(">H", len(encoded)) + encoded


def encode_manifest(manifest: Manifest) -> bytes:
    checked = validate_manifest(manifest)
    output = bytearray(struct.pack(">IH", MAGIC, VERSION))
    output.extend(_uuid_bytes(checked.batch_id))
    output.extend(struct.pack(">H", len(checked.entries)))
    for entry in checked.entries:
        output.extend(_uuid_bytes(entry.file_id))
        output.extend(_text(entry.name, MAX_NAME_BYTES))
        output.extend(_text(entry.mime_type, MAX_MIME_BYTES))
        output.extend(struct.pack(">q", entry.size_bytes))
        output.extend(entry.sha256)
    if len(output) > MAX_MANIFEST_BYTES:
        raise ValueError("V2 manifest exceeds maximum encoded size.")
    return bytes(output)


def decode_manifest(payload: bytes) -> Manifest:
    if not isinstance(payload, bytes) or not 1 <= len(payload) <= MAX_MANIFEST_BYTES:
        raise ValueError("Invalid V2 manifest payload size.")
    view = memoryview(payload)
    offset = 0

    def take(size: int) -> bytes:
        nonlocal offset
        if size < 0 or offset + size > len(view):
            raise ValueError("Truncated V2 manifest.")
        result = bytes(view[offset:offset + size])
        offset += size
        return result

    def read_text(maximum: int) -> str:
        length = struct.unpack(">H", take(2))[0]
        if not 1 <= length <= maximum:
            raise ValueError("Invalid V2 text length.")
        try:
            return take(length).decode("utf-8", errors="strict")
        except UnicodeDecodeError as exc:
            raise ValueError("Invalid UTF-8 in V2 manifest.") from exc

    magic, version = struct.unpack(">IH", take(6))
    if magic != MAGIC or version != VERSION:
        raise ValueError("Unsupported Ultra Share V2 manifest header.")
    batch_id = str(uuid.UUID(bytes=take(16)))
    count = struct.unpack(">H", take(2))[0]
    if not 1 <= count <= MAX_FILES:
        raise ValueError("Invalid V2 manifest entry count.")
    entries = []
    for _ in range(count):
        file_id = str(uuid.UUID(bytes=take(16)))
        name = read_text(MAX_NAME_BYTES)
        mime = read_text(MAX_MIME_BYTES)
        size = struct.unpack(">q", take(8))[0]
        digest = take(DIGEST_BYTES)
        entries.append(FileEntry(file_id, name, mime, size, digest))
    if offset != len(view):
        raise ValueError("Trailing bytes in V2 manifest.")
    return validate_manifest(Manifest(batch_id, tuple(entries)))


# ---- V2 authenticated batch data session ---------------------------------
# These routines mirror UltraShareBatchTransport.kt. V1 functions in
# ultra_share_pc.py are intentionally left untouched for legacy peers.
import hmac
import ipaddress
import os
import socket
import struct
import time
import threading
import mimetypes
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

HANDSHAKE_MAGIC = MAGIC
DEFAULT_V2_PORT = 38742
CHALLENGE_BYTES = 32
NONCE_PREFIX_BYTES = 4
GCM_TAG_BYTES = 16
BUFFER_BYTES = 1024 * 1024
FRAME_BYTES = 256 * 1024
SESSION_TIMEOUT_SECONDS = 30
TYPE_CHUNK = 1
TYPE_FILE_END = 2
TYPE_BATCH_END = 3
STATUS_PENDING = 0
STATUS_TRANSFERRING = 1
STATUS_COMPLETED = 2
STATUS_FAILED = 3
STATUS_SKIPPED = 4


def derive_v2_pairing_key(secret: str, challenge: bytes) -> bytes:
    if len(challenge) != CHALLENGE_BYTES:
        raise ValueError("Invalid V2 pairing challenge.")
    if len(secret) == 9 and secret.isascii() and secret.isdigit():
        return hashlib.pbkdf2_hmac(
            "sha256", secret.encode("ascii"), challenge[:16], 210_000, dklen=32
        )
    if len(secret) == 64 and all(ch in "0123456789abcdefABCDEF" for ch in secret):
        return bytes.fromhex(secret)
    raise ValueError("Invalid Ultra Share pairing code.")


def derive_v2_key(pairing_key: bytes, challenge: bytes, direction: str) -> bytes:
    if len(pairing_key) != 32 or len(challenge) != CHALLENGE_BYTES:
        raise ValueError("Invalid V2 key derivation input.")
    if direction not in {"client-to-server", "server-to-client"}:
        raise ValueError("Invalid V2 key direction.")
    label = f"MalekPlay Ultra Share v2/{direction}".encode("utf-8")
    return hmac.new(pairing_key, challenge + label, hashlib.sha256).digest()


def v2_nonce(prefix: bytes, sequence: int) -> bytes:
    if len(prefix) != NONCE_PREFIX_BYTES or not 0 <= sequence < 2**64:
        raise ValueError("Invalid V2 frame nonce.")
    return prefix + struct.pack(">Q", sequence)


def v2_write_frame(sock: socket.socket, key: bytes, prefix: bytes,
                   sequence: int, plaintext: bytes) -> None:
    if len(plaintext) > MAX_MANIFEST_BYTES + 64:
        raise ValueError("V2 frame exceeds the maximum plaintext size.")
    encrypted = AESGCM(key).encrypt(v2_nonce(prefix, sequence), plaintext, None)
    sock.sendall(struct.pack(">I", len(encrypted)) + encrypted)


def v2_read_frame(sock: socket.socket, key: bytes, prefix: bytes,
                  sequence: int, maximum_plain: int) -> bytes:
    if not 0 <= maximum_plain <= MAX_MANIFEST_BYTES + 64:
        raise ValueError("Invalid V2 frame limit.")
    encrypted_size = struct.unpack(">I", recv_exact(sock, 4))[0]
    if not GCM_TAG_BYTES <= encrypted_size <= maximum_plain + GCM_TAG_BYTES:
        raise ValueError("Invalid encrypted V2 frame length.")
    plain = AESGCM(key).decrypt(
        v2_nonce(prefix, sequence), recv_exact(sock, encrypted_size), None
    )
    if len(plain) > maximum_plain:
        raise ValueError("Decrypted V2 frame exceeds its limit.")
    return plain


def _java_write_utf(value: str) -> bytes:
    # DataOutputStream.writeUTF uses modified UTF-8 over UTF-16 code units.
    encoded = bytearray()
    units = value.encode("utf-16-be", errors="surrogatepass")
    for index in range(0, len(units), 2):
        code = (units[index] << 8) | units[index + 1]
        if 0x0001 <= code <= 0x007F:
            encoded.append(code)
        elif code <= 0x07FF:
            encoded.extend((0xC0 | (code >> 6), 0x80 | (code & 0x3F)))
        else:
            encoded.extend((0xE0 | (code >> 12), 0x80 | ((code >> 6) & 0x3F), 0x80 | (code & 0x3F)))
    if len(encoded) > 65535:
        raise ValueError("Modified UTF-8 string is too long.")
    return struct.pack(">H", len(encoded)) + encoded


def _java_read_utf(payload: bytes, offset: int) -> tuple[str, int]:
    if offset + 2 > len(payload):
        raise ValueError("Truncated modified UTF-8 length.")
    length = struct.unpack_from(">H", payload, offset)[0]
    offset += 2
    end = offset + length
    if end > len(payload):
        raise ValueError("Truncated modified UTF-8 value.")
    units: list[int] = []
    while offset < end:
        first = payload[offset]
        offset += 1
        if first & 0x80 == 0:
            units.append(first)
        elif first & 0xE0 == 0xC0:
            if offset >= end:
                raise ValueError("Malformed modified UTF-8.")
            second = payload[offset]
            offset += 1
            if second & 0xC0 != 0x80:
                raise ValueError("Malformed modified UTF-8.")
            units.append(((first & 0x1F) << 6) | (second & 0x3F))
        elif first & 0xF0 == 0xE0:
            if offset + 1 >= end:
                raise ValueError("Malformed modified UTF-8.")
            second, third = payload[offset], payload[offset + 1]
            offset += 2
            if second & 0xC0 != 0x80 or third & 0xC0 != 0x80:
                raise ValueError("Malformed modified UTF-8.")
            units.append(((first & 0x0F) << 12) | ((second & 0x3F) << 6) | (third & 0x3F))
        else:
            raise ValueError("Malformed modified UTF-8.")
    raw = b"".join(struct.pack(">H", unit) for unit in units)
    return raw.decode("utf-16-be", errors="surrogatepass"), end


def _safe_v2_name(value: str) -> str:
    # Keep the same forbidden code points and UTF-8 byte limit as Android.
    import unicodedata
    leaf = value.replace("\\", "/").rsplit("/", 1)[-1]
    cleaned = "".join(
        ch for ch in leaf
        if not (ord(ch) < 32 or 127 <= ord(ch) <= 159)
        and unicodedata.category(ch) != "Cf"
        and ch not in ':*?"<>|'
    ).strip().strip(".")
    while cleaned and len(cleaned.encode("utf-8")) > MAX_NAME_BYTES:
        cleaned = cleaned[:-1]
    if not cleaned or cleaned in {".", ".."}:
        raise ValueError("Invalid V2 filename.")
    return cleaned


def _v2_transfer_id(entry: FileEntry) -> str:
    name = entry.name.encode("utf-8")
    identity = struct.pack(">I", len(name)) + name + struct.pack(">q", entry.size_bytes) + entry.sha256
    return hashlib.sha256(identity).digest()[:12].hex()


def _read_v2_offsets(payload: bytes, manifest: Manifest) -> dict[str, int]:
    if len(payload) < 2:
        raise ValueError("Truncated V2 resume offsets.")
    count = struct.unpack_from(">H", payload, 0)[0]
    if count != len(manifest.entries):
        raise ValueError("V2 resume offset count does not match manifest.")
    offset = 2
    result: dict[str, int] = {}
    by_id = {entry.file_id: entry for entry in manifest.entries}
    for _ in range(count):
        file_id, offset = _java_read_utf(payload, offset)
        if offset + 8 > len(payload) or file_id not in by_id or file_id in result:
            raise ValueError("Invalid V2 resume offset record.")
        value = struct.unpack_from(">q", payload, offset)[0]
        offset += 8
        if not 0 <= value <= by_id[file_id].size_bytes:
            raise ValueError("V2 resume offset is outside the file.")
        result[file_id] = value
    if offset != len(payload) or set(result) != set(by_id):
        raise ValueError("V2 resume offsets are incomplete.")
    return result


def _encode_v2_offsets(manifest: Manifest, offsets: dict[str, int]) -> bytes:
    output = bytearray(struct.pack(">H", len(manifest.entries)))
    for entry in manifest.entries:
        value = offsets[entry.file_id]
        if not 0 <= value <= entry.size_bytes:
            raise ValueError("Invalid local V2 resume offset.")
        output.extend(_java_write_utf(entry.file_id))
        output.extend(struct.pack(">q", value))
    return bytes(output)


def _encode_v2_results(manifest: Manifest, results: list[dict]) -> bytes:
    if len(results) != len(manifest.entries):
        raise ValueError("V2 result count does not match manifest.")
    output = bytearray(struct.pack(">H", len(results)))
    expected = {entry.file_id: entry for entry in manifest.entries}
    seen: set[str] = set()
    for result in results:
        file_id = result["file_id"]
        if file_id not in expected or file_id in seen:
            raise ValueError("Invalid V2 result file id.")
        seen.add(file_id)
        status = result["status"]
        transferred = result["transferred"]
        error = result.get("error") or ""
        if status not in range(5) or not 0 <= transferred <= expected[file_id].size_bytes:
            raise ValueError("Invalid V2 result values.")
        if status == STATUS_COMPLETED and (transferred != expected[file_id].size_bytes or error):
            raise ValueError("Completed V2 result must report the full file and no error.")
        output.extend(_java_write_utf(file_id))
        output.append(status)
        output.extend(struct.pack(">q", transferred))
        output.extend(_java_write_utf(error[:240]))
    return bytes(output)


def _read_v2_results(payload: bytes, manifest: Manifest) -> list[dict]:
    if len(payload) < 2:
        raise ValueError("Truncated V2 results.")
    count = struct.unpack_from(">H", payload, 0)[0]
    if count != len(manifest.entries):
        raise ValueError("V2 result count does not match manifest.")
    offset = 2
    by_id = {entry.file_id: entry for entry in manifest.entries}
    results = []
    seen: set[str] = set()
    for _ in range(count):
        file_id, offset = _java_read_utf(payload, offset)
        if offset + 9 > len(payload) or file_id not in by_id or file_id in seen:
            raise ValueError("Invalid V2 result record.")
        seen.add(file_id)
        status = payload[offset]
        transferred = struct.unpack_from(">q", payload, offset + 1)[0]
        offset += 9
        error, offset = _java_read_utf(payload, offset)
        entry = by_id[file_id]
        if status not in range(5) or not 0 <= transferred <= entry.size_bytes:
            raise ValueError("Invalid V2 result status or byte count.")
        if status == STATUS_COMPLETED and (transferred != entry.size_bytes or error):
            raise ValueError("Invalid completed V2 result.")
        results.append({
            "file_id": file_id, "status": status,
            "transferred": transferred, "error": error or None,
        })
    if offset != len(payload) or seen != set(by_id):
        raise ValueError("V2 results contain missing or trailing data.")
    return results


def _atomic_text(path: Path, content: str) -> None:
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("w", encoding="utf-8", newline="") as stream:
        stream.write(content)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def _hash_path(path: Path) -> bytes:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while True:
            block = stream.read(BUFFER_BYTES)
            if not block:
                break
            digest.update(block)
    return digest.digest()


def _unique_v2_destination(directory: Path, filename: str) -> Path:
    name = _safe_v2_name(filename)
    candidate = directory / name
    if not candidate.exists():
        return candidate
    suffix = Path(name).suffix
    stem = name[:-len(suffix)] if suffix else name
    for index in range(1, 10_000):
        candidate = directory / f"{stem} ({index}){suffix}"
        if not candidate.exists():
            return candidate
    raise OSError("Unable to allocate a unique V2 destination name.")


def receive_batch_v2(port: int, secret: str, directory: Path,
                     peer_identity: str | None = None) -> list[dict]:
    """Accept one Android V2 batch. Call repeatedly from a receiver loop.

    The Android control channel must already have approved the peer before it
    sends data. The desktop receiver validates the V2 pairing proof and keeps
    per-peer durable partials/receipts; V1 listener code is independent.
    """
    directory = Path(directory).expanduser().resolve()
    directory.mkdir(parents=True, exist_ok=True)
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(("0.0.0.0", port))
        server.listen(8)
        server.settimeout(1.0)
        while True:
            try:
                connection, address = server.accept()
                break
            except socket.timeout:
                continue

    with connection:
        if not _is_private_v2_peer(address[0]):
            raise PermissionError("Rejected V2 peer outside the private LAN.")
        connection.settimeout(SESSION_TIMEOUT_SECONDS)
        challenge = secrets.token_bytes(CHALLENGE_BYTES)
        connection.sendall(struct.pack(">I", HANDSHAKE_MAGIC) + challenge)
        proof = recv_exact(connection, 32)
        pairing_key = derive_v2_pairing_key(secret, challenge)
        authenticated = hmac.compare_digest(
            proof, hmac.new(pairing_key, challenge, hashlib.sha256).digest()
        )
        with _CONTROL_PEER_LOCK:
            active_peer = _CONTROL_PEER_SESSIONS.get(address[0])
            session_active = (
                active_peer is not None
                and active_peer[1].fileno() >= 0
                and not active_peer[1]._closed
            )
        approved = authenticated and session_active
        connection.sendall(b"\x01" if approved else b"\x00")
        if not approved:
            if not authenticated:
                raise PermissionError("V2 pairing authentication failed.")
            raise PermissionError("No approved MPC2 control session exists for this peer.")
        nonce_prefix = secrets.token_bytes(NONCE_PREFIX_BYTES)
        connection.sendall(nonce_prefix)
        receive_key = derive_v2_key(pairing_key, challenge, "client-to-server")
        send_key = derive_v2_key(pairing_key, challenge, "server-to-client")
        receive_sequence = 0
        send_sequence = 0

        manifest = decode_manifest(v2_read_frame(
            connection, receive_key, nonce_prefix, receive_sequence,
            MAX_MANIFEST_BYTES,
        ))
        receive_sequence += 1

        identity = peer_identity or active_peer[0] or address[0]
        peer_folder = hashlib.sha256(identity.encode("utf-8")).hexdigest()[:24]
        state = directory / ".ultrashare-v2-state" / peer_folder
        state.mkdir(parents=True, exist_ok=True)
        offsets: dict[str, int] = {}
        partials: dict[str, Path] = {}
        completed: dict[str, Path] = {}

        for entry in manifest.entries:
            transfer_id = _v2_transfer_id(entry)
            partial = state / f".ultrashare-v2-{transfer_id}.part"
            receipt = state / f".ultrashare-v2-{transfer_id}.received"
            existing: Path | None = None
            if receipt.is_file():
                try:
                    size_text, digest_text, name_text = receipt.read_text("utf-8").split(":", 2)
                    safe = _safe_v2_name(name_text)
                    candidate = directory / safe
                    if (
                        int(size_text) == entry.size_bytes
                        and hmac.compare_digest(digest_text, entry.sha256.hex())
                        and candidate.is_file()
                        and candidate.stat().st_size == entry.size_bytes
                        and hmac.compare_digest(_hash_path(candidate), entry.sha256)
                    ):
                        existing = candidate
                except (OSError, ValueError):
                    existing = None
            if existing is not None:
                completed[entry.file_id] = existing
                offsets[entry.file_id] = entry.size_bytes
            else:
                if partial.exists() and partial.stat().st_size > entry.size_bytes:
                    partial.unlink()
                offsets[entry.file_id] = partial.stat().st_size if partial.is_file() else 0
            partials[entry.file_id] = partial

        v2_write_frame(connection, send_key, nonce_prefix, send_sequence,
                       _encode_v2_offsets(manifest, offsets))
        send_sequence += 1

        outcomes: list[dict] = []
        for index, entry in enumerate(manifest.entries):
            partial = partials[entry.file_id]
            transferred = offsets[entry.file_id]
            if entry.file_id not in completed:
                with partial.open("ab" if transferred else "wb") as output:
                    try:
                        while transferred < entry.size_bytes:
                            remaining = entry.size_bytes - transferred
                            maximum = min(FRAME_BYTES, remaining + 7)
                            payload = v2_read_frame(
                                connection, receive_key, nonce_prefix,
                                receive_sequence, maximum,
                            )
                            receive_sequence += 1
                            if len(payload) < 7 or payload[0] != TYPE_CHUNK:
                                raise ValueError("Unexpected V2 chunk frame.")
                            file_index, length = struct.unpack_from(">HI", payload, 1)
                            chunk = payload[7:]
                            if (
                                file_index != index
                                or length != len(chunk)
                                or not 1 <= length <= FRAME_BYTES - 8
                                or length > remaining
                            ):
                                raise ValueError("Invalid V2 chunk bounds or file index.")
                            output.write(chunk)
                            transferred += length
                    finally:
                        output.flush()
                        os.fsync(output.fileno())

            end_frame = v2_read_frame(
                connection, receive_key, nonce_prefix, receive_sequence, 64
            )
            receive_sequence += 1
            if end_frame != bytes((TYPE_FILE_END,)) + struct.pack(">H", index):
                raise ValueError("Invalid V2 file-end frame.")

            destination = completed.get(entry.file_id)
            if destination is None:
                if partial.stat().st_size != entry.size_bytes or not hmac.compare_digest(
                    _hash_path(partial), entry.sha256
                ):
                    partial.unlink(missing_ok=True)
                    outcomes.append({
                        "file_id": entry.file_id, "status": STATUS_FAILED,
                        "transferred": transferred, "error": "SHA-256 verification failed.",
                        "path": None,
                    })
                    continue
                destination = _unique_v2_destination(directory, entry.name)
                receipt = state / f".ultrashare-v2-{_v2_transfer_id(entry)}.received"
                receipt_text = f"{entry.size_bytes}:{entry.sha256.hex()}:{destination.name}"
                _atomic_text(receipt, receipt_text)
                try:
                    os.replace(partial, destination)
                except OSError:
                    receipt.unlink(missing_ok=True)
                    raise
            outcomes.append({
                "file_id": entry.file_id, "status": STATUS_COMPLETED,
                "transferred": entry.size_bytes, "error": None,
                "path": str(destination),
            })

        batch_end = v2_read_frame(
            connection, receive_key, nonce_prefix, receive_sequence, 64
        )
        receive_sequence += 1
        if batch_end != bytes((TYPE_BATCH_END,)):
            raise ValueError("Invalid V2 batch-end frame.")
        v2_write_frame(
            connection, send_key, nonce_prefix, send_sequence,
            _encode_v2_results(manifest, outcomes),
        )
        return outcomes


def _is_private_v2_peer(host: str) -> bool:
    try:
        address = ipaddress.ip_address(host)
        return isinstance(address, ipaddress.IPv4Address) and (
            address.is_private or address.is_link_local
        ) and not (address.is_loopback or address.is_multicast or address.is_unspecified)
    except ValueError:
        return False


def _build_local_manifest(paths: list[Path]) -> tuple[Manifest, list[Path]]:
    if not 1 <= len(paths) <= MAX_FILES:
        raise ValueError("Select between 1 and 512 files for a V2 batch.")
    entries: list[FileEntry] = []
    resolved: list[Path] = []
    for source in paths:
        path = Path(source).expanduser().resolve(strict=True)
        if not path.is_file():
            raise ValueError(f"Not a regular file: {path}")
        size = path.stat().st_size
        if not 0 <= size <= MAX_FILE_BYTES:
            raise ValueError(f"File size outside V2 limit: {path.name}")
        digest = _hash_path(path)
        mime = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        entry = FileEntry(str(uuid.uuid4()), _safe_v2_name(path.name), mime, size, digest)
        entries.append(entry)
        resolved.append(path)
    manifest = Manifest(str(uuid.uuid4()), tuple(entries))
    validate_manifest(manifest)
    return manifest, resolved


def send_batch_v2(host: str, port: int, secret: str, paths: list[Path]) -> list[dict]:
    """Send a batch to a V2 receiver.

    Android receivers additionally require their separately approved control
    session. This function implements only the authenticated V2 data plane;
    callers must establish that control-plane approval before invoking it.
    """
    if not _is_private_v2_peer(host):
        raise ValueError("Use the receiver's private LAN IPv4 address.")
    manifest, sources = _build_local_manifest(paths)
    with socket.create_connection((host, port), timeout=20) as connection:
        connection.settimeout(SESSION_TIMEOUT_SECONDS)
        magic = struct.unpack(">I", recv_exact(connection, 4))[0]
        if magic != HANDSHAKE_MAGIC:
            raise ValueError("The peer does not expose the Ultra Share V2 data port.")
        challenge = recv_exact(connection, CHALLENGE_BYTES)
        pairing_key = derive_v2_pairing_key(secret, challenge)
        connection.sendall(hmac.new(pairing_key, challenge, hashlib.sha256).digest())
        if recv_exact(connection, 1) != b"\x01":
            raise PermissionError("The V2 receiver rejected the pairing code or session.")
        nonce_prefix = recv_exact(connection, NONCE_PREFIX_BYTES)
        send_key = derive_v2_key(pairing_key, challenge, "client-to-server")
        receive_key = derive_v2_key(pairing_key, challenge, "server-to-client")
        send_sequence = 0
        receive_sequence = 0
        v2_write_frame(connection, send_key, nonce_prefix, send_sequence,
                       encode_manifest(manifest))
        send_sequence += 1
        offsets = _read_v2_offsets(
            v2_read_frame(connection, receive_key, nonce_prefix, receive_sequence, 32 * 1024),
            manifest,
        )
        receive_sequence += 1

        for index, (entry, source) in enumerate(zip(manifest.entries, sources)):
            offset = offsets[entry.file_id]
            transferred = offset
            with source.open("rb") as stream:
                stream.seek(offset)
                while transferred < entry.size_bytes:
                    chunk = stream.read(min(FRAME_BYTES - 8, entry.size_bytes - transferred))
                    if not chunk:
                        raise OSError(f"Source file changed while sending: {source.name}")
                    frame = bytes((TYPE_CHUNK,)) + struct.pack(">HI", index, len(chunk)) + chunk
                    v2_write_frame(connection, send_key, nonce_prefix, send_sequence, frame)
                    send_sequence += 1
                    transferred += len(chunk)
                if stream.read(1):
                    raise OSError(f"Source file grew while sending: {source.name}")
            v2_write_frame(
                connection, send_key, nonce_prefix, send_sequence,
                bytes((TYPE_FILE_END,)) + struct.pack(">H", index),
            )
            send_sequence += 1

        v2_write_frame(
            connection, send_key, nonce_prefix, send_sequence,
            bytes((TYPE_BATCH_END,)),
        )
        send_sequence += 1
        results = _read_v2_results(
            v2_read_frame(connection, receive_key, nonce_prefix, receive_sequence,
                          MAX_MANIFEST_BYTES),
            manifest,
        )
        return results


# ---- Android MPC2 control channel -----------------------------------------
CONTROL_PORT = 38744
CONTROL_MAGIC = 0x4D504332  # MPC2
CONTROL_VERSION = 1
CONTROL_AAD = b"MPULTRA_CONTROL_DECISION|1"
_CONTROL_PEER_SESSIONS: dict[str, tuple[str, socket.socket]] = {}
_CONTROL_PEER_LOCK = threading.Lock()


def _control_subkey(master: bytes, label: str) -> bytes:
    return hmac.new(master, ("MPULTRA_CONTROL|" + label + "|1").encode("utf-8"),
                    hashlib.sha256).digest()


def _control_request_mac(key: bytes, challenge: bytes, request_id: str,
                         peer_name: bytes) -> bytes:
    payload = (
        _java_write_utf("MPULTRA_CONTROL_REQUEST")
        + struct.pack(">i", CONTROL_VERSION)
        + challenge
        + _java_write_utf(request_id)
        + struct.pack(">H", len(peer_name))
        + peer_name
    )
    return hmac.new(_control_subkey(key, "request-authentication"),
                    payload, hashlib.sha256).digest()


def _control_encrypt(key: bytes, nonce: bytes, plaintext: str) -> bytes:
    return AESGCM(_control_subkey(key, "decision-encryption")).encrypt(
        nonce, plaintext.encode("utf-8"), CONTROL_AAD
    )


def _control_decrypt(key: bytes, nonce: bytes, ciphertext: bytes) -> str:
    return AESGCM(_control_subkey(key, "decision-encryption")).decrypt(
        nonce, ciphertext, CONTROL_AAD
    ).decode("utf-8", errors="strict")


def _read_java_utf_socket(sock: socket.socket) -> str:
    length = struct.unpack(">H", recv_exact(sock, 2))[0]
    encoded = struct.pack(">H", length) + recv_exact(sock, length)
    value, end = _java_read_utf(encoded, 0)
    if end != len(encoded):
        raise ValueError("Malformed Java UTF control field.")
    return value


def _write_java_utf_socket(sock: socket.socket, value: str) -> None:
    sock.sendall(_java_write_utf(value))


def serve_control_once(port: int, secret: str, local_name: str,
                       approve: callable) -> None:
    """Serve one Android MPC2 control request and retain approved heartbeat."""
    safe_local_name = str(local_name)[:48]
    while safe_local_name and len(safe_local_name.encode("utf-8")) > 96:
        safe_local_name = safe_local_name[:-1]
    if not safe_local_name.strip() or any(
        ord(ch) < 32 or 127 <= ord(ch) <= 159 for ch in safe_local_name
    ):
        raise ValueError("Invalid local control-channel device name.")
    name_bytes = safe_local_name.encode("utf-8")
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(("0.0.0.0", port))
        server.listen(8)
        server.settimeout(1.0)
        while True:
            try:
                connection, address = server.accept()
                break
            except socket.timeout:
                continue

    with connection:
        if not _is_private_v2_peer(address[0]):
            raise PermissionError("Rejected control peer outside the private LAN.")
        connection.settimeout(8.0)
        challenge = secrets.token_bytes(CHALLENGE_BYTES)
        connection.sendall(struct.pack(">IB", CONTROL_MAGIC, CONTROL_VERSION) + challenge)
        request_id = _read_java_utf_socket(connection)
        if not 16 <= len(request_id) <= 64 or any(
            not (char.isalnum() or char == "-") for char in request_id
        ):
            raise ValueError("Invalid MPC2 request id.")
        name_length = struct.unpack(">H", recv_exact(connection, 2))[0]
        if not 1 <= name_length <= 96:
            raise ValueError("Invalid MPC2 peer name length.")
        peer_name_bytes = recv_exact(connection, name_length)
        peer_name = peer_name_bytes.decode("utf-8", errors="strict")
        if not peer_name.strip() or any(ord(ch) < 32 or 127 <= ord(ch) <= 159 for ch in peer_name):
            raise ValueError("Invalid MPC2 peer name.")
        supplied_mac = recv_exact(connection, 32)
        pairing_key = derive_v2_pairing_key(secret, challenge)
        expected_mac = _control_request_mac(
            pairing_key, challenge, request_id, peer_name_bytes
        )
        if not hmac.compare_digest(supplied_mac, expected_mac):
            raise PermissionError("MPC2 pairing authentication failed.")

        accepted = bool(approve(peer_name, address[0]))
        peer_id = (
            request_id[-32:].lower()
            if len(request_id) == 64 and all(ch in "0123456789abcdefABCDEF" for ch in request_id[-32:])
            else None
        )
        if accepted:
            with _CONTROL_PEER_LOCK:
                _CONTROL_PEER_SESSIONS[address[0]] = (peer_id or address[0], connection)
        try:
            encoded_local_name = name_bytes.hex()
            response = (
                f"ACCEPT|{request_id}|{encoded_local_name}"
                if accepted else f"REJECT|{request_id}"
            )
            nonce = secrets.token_bytes(12)
            frame = nonce + _control_encrypt(pairing_key, nonce, response)
            if len(frame) > 512:
                raise ValueError("MPC2 decision frame exceeds its limit.")
            connection.sendall(struct.pack(">I", len(frame)) + frame)
            if not accepted:
                return

            connection.settimeout(18.0)
            while True:
                command = _read_java_utf_socket(connection)
                if command == "PING":
                    _write_java_utf_socket(connection, "PONG")
                elif command == "CLOSE":
                    return
                else:
                    raise ValueError("Unknown MPC2 control command.")
        finally:
            if peer_id:
                with _CONTROL_PEER_LOCK:
                    current = _CONTROL_PEER_SESSIONS.get(address[0])
                    if current is not None and current[1] is connection:
                        _CONTROL_PEER_SESSIONS.pop(address[0], None)


class ControlSession:
    """Keeps the separately authenticated Android control socket alive."""
    def __init__(self, sock: socket.socket):
        self.socket = sock
        self._stop = threading.Event()
        self._lock = threading.Lock()
        self.error: Exception | None = None
        self._thread = threading.Thread(
            target=self._heartbeat_loop, name="ultrashare-control-heartbeat", daemon=True
        )
        self._thread.start()

    def _heartbeat_loop(self) -> None:
        while not self._stop.wait(5.0):
            try:
                with self._lock:
                    _write_java_utf_socket(self.socket, "PING")
                    if _read_java_utf_socket(self.socket) != "PONG":
                        raise ConnectionError("Invalid MPC2 heartbeat response.")
            except Exception as failure:
                self.error = failure
                self._stop.set()
                break

    def close(self) -> None:
        self._stop.set()
        try:
            with self._lock:
                if self.socket.fileno() >= 0:
                    self.socket.settimeout(1.0)
                    _write_java_utf_socket(self.socket, "CLOSE")
        except OSError:
            pass
        try:
            self.socket.close()
        finally:
            if self._thread.is_alive():
                self._thread.join(timeout=1.5)

    def __enter__(self):
        return self

    def __exit__(self, _kind, _value, _traceback):
        self.close()


def connect_control_session(host: str, port: int, secret: str,
                             local_name: str = "MalekPlay Desktop") -> ControlSession:
    """Connect to Android MPC2, where the phone owner explicitly approves."""
    if not _is_private_v2_peer(host):
        raise ValueError("Use the Android device's private LAN IPv4 address.")
    safe_local_name = str(local_name)[:48]
    while safe_local_name and len(safe_local_name.encode("utf-8")) > 96:
        safe_local_name = safe_local_name[:-1]
    if not safe_local_name.strip() or any(
        ord(ch) < 32 or 127 <= ord(ch) <= 159 for ch in safe_local_name
    ):
        raise ValueError("Invalid local MPC2 device name.")
    name_bytes = safe_local_name.encode("utf-8")
    sock = socket.create_connection((host, port), timeout=8.0)
    try:
        sock.settimeout(8.0)
        magic, version = struct.unpack(">IB", recv_exact(sock, 5))
        if magic != CONTROL_MAGIC or version != CONTROL_VERSION:
            raise ValueError("Android MPC2 control protocol is not available.")
        challenge = recv_exact(sock, CHALLENGE_BYTES)
        pairing_key = derive_v2_pairing_key(secret, challenge)
        request_id = uuid.uuid4().hex
        sock.sendall(_java_write_utf(request_id))
        sock.sendall(struct.pack(">H", len(name_bytes)) + name_bytes)
        sock.sendall(_control_request_mac(
            pairing_key, challenge, request_id, name_bytes
        ))
        frame_size = struct.unpack(">I", recv_exact(sock, 4))[0]
        if not 28 <= frame_size <= 512:
            raise ValueError("Invalid MPC2 decision frame size.")
        frame = recv_exact(sock, frame_size)
        response = _control_decrypt(pairing_key, frame[:12], frame[12:])
        parts = response.split("|", 3)
        if len(parts) < 2 or parts[1] != request_id:
            raise ValueError("MPC2 decision does not match this request.")
        if parts[0] != "ACCEPT":
            raise PermissionError("The Android user rejected the control-channel request.")
        sock.settimeout(18.0)
        return ControlSession(sock)
    except Exception:
        sock.close()
        raise
