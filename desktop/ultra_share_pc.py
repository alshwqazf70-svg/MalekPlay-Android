#!/usr/bin/env python3
"""MalekPlay Ultra Share desktop companion (protocol v1).

Requires Python 3.10+ and: pip install -r requirements.txt
The pairing secret is used only in memory and is never written to disk.
"""
from __future__ import annotations

import argparse
import getpass
import hashlib
import hmac
import ipaddress
import mimetypes
import os
import secrets
import socket
import struct
import sys
import threading
import time
import unicodedata
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAGIC = 0x4D505531
VERSION = 1
DEFAULT_PORT = 38741
DISCOVERY_PORT = 38742
CHUNK_BYTES = 256 * 1024
MAX_FILE_BYTES = 100 * 1024 * 1024 * 1024
GCM_TAG_BYTES = 16
PROBE = b"MPULTRA_DISCOVER|1"
OFFER_PREFIX = "MPULTRA_OFFER|1|"


def get_secret(value: str | None) -> str:
    raw = value or getpass.getpass("Pairing code: ")
    is_pin = len(raw) == 12 and raw.isascii() and raw.isdigit()
    is_legacy_key = len(raw) == 64 and all(
        ch in "0123456789abcdefABCDEF" for ch in raw
    )
    if not (is_pin or is_legacy_key):
        raise ValueError("Pairing code must be 12 digits (or a legacy 64-character hex key).")
    return raw


def derive_pairing_key(secret: str, challenge: bytes) -> bytes:
    """Match Android's PBKDF2 PIN derivation; retain legacy hex-key support."""
    if len(secret) == 12 and secret.isascii() and secret.isdigit():
        return hashlib.pbkdf2_hmac(
            "sha256", secret.encode("ascii"), challenge[:16], 210_000, dklen=32
        )
    if len(secret) == 64 and all(ch in "0123456789abcdefABCDEF" for ch in secret):
        return bytes.fromhex(secret)
    raise ValueError("Invalid Ultra Share pairing code.")


def is_lan_ipv4(value: str) -> bool:
    try:
        address = ipaddress.ip_address(value)
        return (
            isinstance(address, ipaddress.IPv4Address)
            and not (address.is_loopback or address.is_multicast or address.is_unspecified)
            and (address.is_private or address.is_link_local)
        )
    except ValueError:
        return False


def private_ipv4(host: str) -> str:
    if not is_lan_ipv4(host):
        raise ValueError("Use the receiver's private IPv4 address on the same LAN.")
    return str(ipaddress.ip_address(host))


def recv_exact(sock: socket.socket, length: int) -> bytes:
    data = bytearray()
    while len(data) < length:
        block = sock.recv(length - len(data))
        if not block:
            raise ConnectionError("The other device closed the connection.")
        data.extend(block)
    return bytes(data)


def derive_key(secret: bytes, challenge: bytes, direction: str) -> bytes:
    label = f"MalekPlay Ultra Share v1/{direction}".encode("utf-8")
    return hmac.new(secret, challenge + label, hashlib.sha256).digest()


def make_nonce(prefix: bytes, sequence: int) -> bytes:
    if len(prefix) != 4 or sequence < 0:
        raise ValueError("Invalid encrypted-frame nonce.")
    return prefix + struct.pack(">Q", sequence)


def write_frame(sock: socket.socket, key: bytes, prefix: bytes,
                sequence: int, plaintext: bytes) -> None:
    encrypted = AESGCM(key).encrypt(make_nonce(prefix, sequence), plaintext, None)
    sock.sendall(struct.pack(">I", len(encrypted)) + encrypted)


def read_frame(sock: socket.socket, key: bytes, prefix: bytes,
               sequence: int, maximum_plain: int) -> bytes:
    encrypted_size = struct.unpack(">I", recv_exact(sock, 4))[0]
    if encrypted_size < GCM_TAG_BYTES or encrypted_size > maximum_plain + GCM_TAG_BYTES:
        raise ValueError("The received encrypted frame has an invalid size.")
    encrypted = recv_exact(sock, encrypted_size)
    plaintext = AESGCM(key).decrypt(make_nonce(prefix, sequence), encrypted, None)
    if len(plaintext) > maximum_plain:
        raise ValueError("The decrypted frame exceeds the protocol limit.")
    return plaintext


def encode_text(value: str, maximum: int) -> bytes:
    encoded = value.encode("utf-8")
    if len(encoded) > maximum:
        raise ValueError("Text field exceeds the protocol limit.")
    return struct.pack(">H", len(encoded)) + encoded


def read_text(data: bytes, offset: int, maximum: int) -> tuple[str, int]:
    if offset + 2 > len(data):
        raise ValueError("Incomplete text field.")
    length = struct.unpack_from(">H", data, offset)[0]
    offset += 2
    if length > maximum or offset + length > len(data):
        raise ValueError("Invalid text field length.")
    return data[offset:offset + length].decode("utf-8"), offset + length


def safe_name(raw: str) -> str:
    name = raw.replace("\\", "/").split("/")[-1]
    name = "".join(
        ch for ch in name
        if not (ord(ch) < 32 or 127 <= ord(ch) <= 159)
        and unicodedata.category(ch) != "Cf"
        and ch not in ':*?"<>|'
    ).strip().strip(".")
    while len(name.encode("utf-8")) > 180:
        name = name[:-1]
    if not name or name in {".", ".."}:
        raise ValueError("The file name is not valid.")
    return name


def parse_metadata(data: bytes) -> tuple[str, int, str, bytes]:
    if len(data) < 2 or struct.unpack_from(">H", data, 0)[0] != VERSION:
        raise ValueError("Unsupported Ultra Share protocol version.")
    name, offset = read_text(data, 2, 768)
    if offset + 8 > len(data):
        raise ValueError("Incomplete file metadata.")
    size = struct.unpack_from(">q", data, offset)[0]
    offset += 8
    mime, offset = read_text(data, offset, 192)
    if offset + 32 != len(data):
        raise ValueError("Invalid file digest metadata.")
    digest = data[offset:offset + 32]
    if size < 0 or size > MAX_FILE_BYTES:
        raise ValueError("The file size is outside the supported range.")
    return safe_name(name), size, mime[:160], digest


def build_transfer_id(filename: str, size: int, digest: bytes) -> str:
    name_bytes = safe_name(filename).encode("utf-8")
    if size < 0 or len(digest) != 32:
        raise ValueError("Invalid transfer identity.")
    identity = (
        struct.pack(">I", len(name_bytes))
        + name_bytes
        + struct.pack(">q", size)
        + digest
    )
    return hashlib.sha256(identity).digest()[:12].hex()


def hash_file(path: Path) -> bytes:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while True:
            block = stream.read(CHUNK_BYTES)
            if not block:
                break
            digest.update(block)
    return digest.digest()


def find_completed_receipt(
    directory: Path, transfer_id: str, size: int, digest: bytes
) -> Path | None:
    receipt = directory / f".ultrashare-{transfer_id}.received"
    if not receipt.is_file():
        return None
    try:
        receipt_size, receipt_digest, receipt_name = receipt.read_text("utf-8").split(":", 2)
        candidate = directory / safe_name(receipt_name)
        if (
            int(receipt_size) == size
            and receipt_digest == digest.hex()
            and candidate.is_file()
            and candidate.stat().st_size == size
            and hmac.compare_digest(hash_file(candidate), digest)
        ):
            return candidate
    except (OSError, ValueError):
        pass
    receipt.unlink(missing_ok=True)
    return None


def read_resume_marker(marker: Path) -> str | None:
    """Treat unreadable resume metadata as stale, untrusted state."""
    if not marker.is_file():
        return None
    try:
        return marker.read_text(encoding="utf-8")
    except OSError:
        return None


def unique_destination(directory: Path, filename: str) -> Path:
    original = directory / filename
    if not original.exists():
        return original
    suffix = Path(filename).suffix
    stem = filename[:-len(suffix)] if suffix else filename
    for index in range(1, 10_000):
        candidate = directory / f"{stem} ({index}){suffix}"
        if not candidate.exists():
            return candidate
    raise OSError("Could not create a unique destination filename.")


def send_once(host: str, port: int, secret: str, source: Path) -> None:
    size = source.stat().st_size
    if size < 0 or size > MAX_FILE_BYTES:
        raise ValueError("The file size is outside the supported range.")
    filename = safe_name(source.name)
    mime = mimetypes.guess_type(filename)[0] or "application/octet-stream"
    digest = hashlib.sha256()
    with source.open("rb") as stream:
        while True:
            block = stream.read(CHUNK_BYTES)
            if not block:
                break
            digest.update(block)
    file_digest = digest.digest()

    with socket.create_connection((host, port), timeout=20) as sock:
        sock.settimeout(20)
        if struct.unpack(">I", recv_exact(sock, 4))[0] != MAGIC:
            raise ValueError("The destination is not a MalekPlay Ultra Share receiver.")
        challenge = recv_exact(sock, 32)
        pairing_key = derive_pairing_key(secret, challenge)
        proof = hmac.new(pairing_key, challenge, hashlib.sha256).digest()
        sock.sendall(proof)
        if recv_exact(sock, 1) != b"\x01":
            raise PermissionError("The receiver rejected the pairing code.")
        nonce_prefix = recv_exact(sock, 4)
        send_key = derive_key(pairing_key, challenge, "client-to-server")
        receive_key = derive_key(pairing_key, challenge, "server-to-client")

        metadata = (
            struct.pack(">H", VERSION)
            + encode_text(filename, 768)
            + struct.pack(">q", size)
            + encode_text(mime[:160], 192)
            + file_digest
        )
        write_frame(sock, send_key, nonce_prefix, 0, metadata)
        offset_data = read_frame(sock, receive_key, nonce_prefix, 0, 64)
        if len(offset_data) != 8:
            raise ValueError("The receiver returned an invalid resume offset.")
        offset = struct.unpack(">q", offset_data)[0]
        if offset < 0 or offset > size:
            raise ValueError("The receiver returned an invalid resume offset.")

        transferred = offset
        sequence = 1
        started = time.monotonic()
        with source.open("rb") as stream:
            stream.seek(offset)
            while transferred < size:
                wanted = min(CHUNK_BYTES, size - transferred)
                block = stream.read(wanted)
                if not block:
                    raise OSError("The source file ended before the expected size.")
                write_frame(sock, send_key, nonce_prefix, sequence, block)
                sequence += 1
                transferred += len(block)
                elapsed = max(time.monotonic() - started, 0.001)
                rate = (transferred - offset) / elapsed
                print(
                    f"\\r{filename}: {transferred * 100 / max(size, 1):5.1f}%  "
                    f"{transferred:,}/{size:,} bytes  {rate / 1024 / 1024:.2f} MB/s",
                    end="",
                    flush=True,
                )
            if stream.read(1):
                raise OSError("The source file changed while it was being sent.")
        write_frame(sock, send_key, nonce_prefix, sequence, b"")
        acknowledgement = read_frame(sock, receive_key, nonce_prefix, 1, 1024)
        if len(acknowledgement) < 3:
            raise ValueError("The receiver returned an invalid acknowledgement.")
        accepted = acknowledgement[0] == 1
        message, end = read_text(acknowledgement, 1, 512)
        if end != len(acknowledgement):
            raise ValueError("The receiver returned malformed acknowledgement data.")
        if not accepted:
            raise OSError(message or "The receiver could not verify the file.")
        print(f"\\nSent and verified: {filename}")


def send_file(host: str, port: int, secret: str, source: Path) -> None:
    host = private_ipv4(host)
    if not source.is_file():
        raise FileNotFoundError(source)
    for attempt in range(3):
        try:
            send_once(host, port, secret, source)
            return
        # Retry transport interruptions only; authentication and local file errors are permanent.
        except (TimeoutError, ConnectionError) as exc:
            if attempt == 2:
                raise
            print(f"\\nConnection interrupted ({exc}); retrying {attempt + 1}/2...")
            time.sleep(attempt + 1)


def receive_one(port: int, secret: str, directory: Path) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(("0.0.0.0", port))
        server.listen(1)
        server.settimeout(1)
        print(f"Waiting for a MalekPlay device on TCP port {port}...")
        while True:
            try:
                connection, address = server.accept()
                break
            except socket.timeout:
                continue

    with connection:
        if not is_lan_ipv4(address[0]):
            raise PermissionError("Rejected a connection outside the private LAN.")
        connection.settimeout(20)
        connection.sendall(struct.pack(">I", MAGIC))
        challenge = secrets.token_bytes(32)
        connection.sendall(challenge)
        supplied = recv_exact(connection, 32)
        pairing_key = derive_pairing_key(secret, challenge)
        authenticated = hmac.compare_digest(
            supplied, hmac.new(pairing_key, challenge, hashlib.sha256).digest()
        )
        connection.sendall(b"\x01" if authenticated else b"\x00")
        if not authenticated:
            raise PermissionError("Pairing authentication failed.")
        nonce_prefix = secrets.token_bytes(4)
        connection.sendall(nonce_prefix)
        receive_key = derive_key(pairing_key, challenge, "client-to-server")
        send_key = derive_key(pairing_key, challenge, "server-to-client")

        metadata = read_frame(connection, receive_key, nonce_prefix, 0, 1024)
        filename, size, _mime, expected_digest = parse_metadata(metadata)
        transfer_id = build_transfer_id(filename, size, expected_digest)
        partial = directory / f".ultrashare-{transfer_id}.part"
        marker = directory / f".ultrashare-{transfer_id}.meta"
        receipt = directory / f".ultrashare-{transfer_id}.received"
        expected_marker = f"{size}:{expected_digest.hex()}"
        # If the previous acknowledgement was lost, confirm the already verified
        # destination instead of creating a second copy of the same file.
        completed = find_completed_receipt(directory, transfer_id, size, expected_digest)
        if completed is not None:
            write_frame(connection, send_key, nonce_prefix, 0, struct.pack(">q", size))
            end = read_frame(connection, receive_key, nonce_prefix, 1, 64)
            if end:
                raise ValueError("Invalid end-of-transfer frame.")
            acknowledgement = b"\x01" + encode_text("File already received and verified.", 512)
            write_frame(connection, send_key, nonce_prefix, 1, acknowledgement)
            print(f"\nAlready received and verified: {completed}")
            return completed

        if read_resume_marker(marker) != expected_marker:
            # An unreadable or damaged sidecar must not trap the receiver in a
            # permanent retry loop. Discard the untrusted partial and rebuild it.
            partial.unlink(missing_ok=True)
            marker.unlink(missing_ok=True)
            temporary_marker = directory / f".ultrashare-{transfer_id}.meta.tmp"
            temporary_marker.write_text(expected_marker, encoding="utf-8")
            temporary_marker.replace(marker)
        if partial.exists() and partial.stat().st_size > size:
            partial.unlink()

        # A connection can drop after the final data block but before its receipt.
        # Reuse a full-sized partial only when its complete SHA-256 still matches.
        if partial.is_file() and partial.stat().st_size == size:
            if not hmac.compare_digest(hash_file(partial), expected_digest):
                partial.unlink()

        offset = partial.stat().st_size if partial.is_file() else 0
        write_frame(connection, send_key, nonce_prefix, 0, struct.pack(">q", offset))

        transferred = offset
        sequence = 1
        with partial.open("ab") as output:
            try:
                while transferred < size:
                    block = read_frame(connection, receive_key, nonce_prefix, sequence, CHUNK_BYTES)
                    sequence += 1
                    if not block or len(block) > size - transferred:
                        raise ValueError("Received a data block with an invalid size.")
                    output.write(block)
                    transferred += len(block)
                    print(f"\\rReceiving {filename}: {transferred * 100 / max(size, 1):5.1f}%", end="", flush=True)
                end = read_frame(connection, receive_key, nonce_prefix, sequence, 64)
                if end:
                    raise ValueError("Invalid end-of-transfer frame.")
            finally:
                # Keep the resume offset durable even when the socket drops mid-file.
                # Sync once per attempt, not once per chunk, to preserve throughput.
                output.flush()
                os.fsync(output.fileno())

        actual_digest = hash_file(partial)
        verified = partial.stat().st_size == size and hmac.compare_digest(expected_digest, actual_digest)
        message = "File integrity verified." if verified else "File integrity verification failed."
        final_path: Path | None = None
        if verified:
            final_path = unique_destination(directory, filename)
            receipt_temp = directory / f".ultrashare-{transfer_id}.received.tmp"
            receipt_content = f"{size}:{expected_digest.hex()}:{final_path.name}"
            with receipt_temp.open("w", encoding="utf-8") as receipt_stream:
                receipt_stream.write(receipt_content)
                receipt_stream.flush()
                os.fsync(receipt_stream.fileno())
            receipt_temp.replace(receipt)
            try:
                partial.replace(final_path)
            except OSError:
                receipt.unlink(missing_ok=True)
                raise
            marker.unlink(missing_ok=True)
        else:
            partial.unlink(missing_ok=True)
            marker.unlink(missing_ok=True)
        acknowledgement = (b"\x01" if verified else b"\x00") + encode_text(message, 512)
        write_frame(connection, send_key, nonce_prefix, 1, acknowledgement)
        if not verified:
            raise OSError(message)
        print(f"\\nReceived and verified: {final_path}")
        return final_path


def discovery_responder(port: int, transfer_port: int, device_name: str) -> None:
    response = f"{OFFER_PREFIX}{transfer_port}|{device_name[:64]}".encode("utf-8")
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.bind(("0.0.0.0", port))
        while True:
            data, address = sock.recvfrom(512)
            if data == PROBE and is_lan_ipv4(address[0]):
                sock.sendto(response, address)


def discover_devices(timeout: float, port: int) -> list[tuple[str, int, str]]:
    found: dict[str, tuple[str, int, str]] = {}
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.bind(("0.0.0.0", 0))
        sock.settimeout(0.5)
        sock.sendto(PROBE, ("255.255.255.255", port))
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                data, address = sock.recvfrom(512)
            except socket.timeout:
                continue
            if not is_lan_ipv4(address[0]):
                continue
            try:
                prefix, version, remote_port, name = data.decode("utf-8").split("|", 3)
                if prefix == "MPULTRA_OFFER" and version == "1":
                    number = int(remote_port)
                    if 1 <= number <= 65535:
                        found[address[0]] = (address[0], number, name[:64])
            except (UnicodeDecodeError, ValueError):
                continue
    return list(found.values())


def main() -> int:
    parser = argparse.ArgumentParser(description="MalekPlay Ultra Share desktop companion")
    sub = parser.add_subparsers(dest="command", required=True)

    receive = sub.add_parser("receive", help="Receive files from Android or another desktop")
    receive.add_argument("--secret", help="12-digit pairing code (legacy 64-character hex key also accepted; prompted if omitted)")
    receive.add_argument("--output", type=Path, default=Path("Received"))
    receive.add_argument("--port", type=int, default=DEFAULT_PORT)
    receive.add_argument("--discovery-port", type=int, default=DISCOVERY_PORT)
    receive.add_argument("--name", default=socket.gethostname())

    send = sub.add_parser("send", help="Send a file to an Android or desktop receiver")
    send.add_argument("--secret", help="12-digit pairing code (legacy 64-character hex key also accepted; prompted if omitted)")
    send.add_argument("--host", required=True, help="Receiver's private IPv4 address")
    send.add_argument("--file", required=True, type=Path)
    send.add_argument("--port", type=int, default=DEFAULT_PORT)

    discover = sub.add_parser("discover", help="Find MalekPlay receivers on the local network")
    discover.add_argument("--timeout", type=float, default=5.0)
    discover.add_argument("--port", type=int, default=DISCOVERY_PORT)

    args = parser.parse_args()
    try:
        if args.command == "discover":
            for host, port, name in discover_devices(args.timeout, args.port):
                print(f"{name}: {host}:{port}")
            return 0

        secret = get_secret(args.secret)
        if args.command == "send":
            send_file(args.host, args.port, secret, args.file)
            return 0

        responder = threading.Thread(
            target=discovery_responder,
            args=(args.discovery_port, args.port, args.name),
            daemon=True,
        )
        responder.start()
        print("Discovery is active. Press Ctrl+C to stop receiving.")
        while True:
            try:
                receive_one(args.port, secret, args.output)
            except (ConnectionError, OSError, ValueError, PermissionError) as exc:
                print(f"Transfer rejected or interrupted: {exc}", file=sys.stderr)
    except KeyboardInterrupt:
        print("\nStopped.")
        return 0
    except Exception as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
