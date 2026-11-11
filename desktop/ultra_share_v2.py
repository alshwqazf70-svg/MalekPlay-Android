"""Wire-compatible Ultra Share v2 manifest codec.

This module intentionally implements only the Android V2 manifest layer. The
existing V1 sender/receiver remains untouched until the complete V2 session
handshake and frame state machine can be exercised against Android.
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
