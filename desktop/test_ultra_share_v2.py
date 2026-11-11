import hashlib
import unittest
import uuid

from ultra_share_v2 import (
    FileEntry,
    Manifest,
    decode_manifest,
    encode_manifest,
    validate_manifest,
    derive_v2_pairing_key,
    derive_v2_key,
    v2_nonce,
    v2_write_frame,
    v2_read_frame,
    _java_write_utf,
    _java_read_utf,
    _encode_v2_offsets,
    _read_v2_offsets,
    _encode_v2_results,
    _read_v2_results,
    STATUS_COMPLETED,
    STATUS_FAILED,
    serve_control_once,
    connect_control_session,
    receive_batch_v2,
    send_batch_v2,
    _control_subkey,
    _control_request_mac,
    _control_encrypt,
    _control_decrypt,
    CONTROL_MAGIC,
    CONTROL_VERSION,
)


class UltraShareV2ManifestTests(unittest.TestCase):
    def test_android_golden_vector_matches_exact_manifest_wire_bytes(self):
        manifest = Manifest(
            "00112233-4455-6677-8899-aabbccddeeff",
            (
                FileEntry(
                    "ffeeddcc-bbaa-9988-7766-554433221100",
                    "مقطع.bin",
                    "application/octet-stream",
                    6,
                    hashlib.sha256(b"golden").digest(),
                ),
            ),
        )
        expected = bytes.fromhex(
            "4d505532000200112233445566778899aabbccddeeff0001"
            "ffeeddccbbaa99887766554433221100000cd985d982d8b7d8b92e62696e"
            "00186170706c69636174696f6e2f6f637465742d73747265616d"
            "0000000000000006"
            "dd56de4137951d9c92681b03416ec15f886b4482a27e3a517d32f085244cbe5d"
        )
        self.assertEqual(expected, encode_manifest(manifest))
        self.assertEqual(manifest, decode_manifest(expected))

    def test_multi_file_manifest_round_trip_and_total_size(self):
        first = FileEntry(str(uuid.uuid4()), "movie.mp4", "video/mp4", 123, hashlib.sha256(b"a").digest())
        second = FileEntry(str(uuid.uuid4()), "book.pdf", "application/pdf", 456, hashlib.sha256(b"b").digest())
        manifest = Manifest(str(uuid.uuid4()), (first, second))
        self.assertEqual(579, decode_manifest(encode_manifest(manifest)).total_bytes)

    def test_zero_byte_document_and_apk_mime_are_allowed(self):
        for mime in ("application/pdf", "application/vnd.android.package-archive", "application/octet-stream"):
            entry = FileEntry(str(uuid.uuid4()), "payload.bin", mime, 0, hashlib.sha256(b"").digest())
            self.assertEqual(entry, decode_manifest(encode_manifest(Manifest(str(uuid.uuid4()), (entry,)))).entries[0])

    def test_rejects_bad_header_truncation_and_trailing_data(self):
        manifest = Manifest(
            str(uuid.uuid4()),
            (FileEntry(str(uuid.uuid4()), "x.bin", "application/octet-stream", 0, hashlib.sha256(b"").digest()),),
        )
        encoded = encode_manifest(manifest)
        for malformed in (b"bad!", encoded[:-1], encoded + b"\x00"):
            with self.subTest(payload=malformed[:8]), self.assertRaises(ValueError):
                decode_manifest(malformed)

    def test_rejects_duplicate_ids_and_unsanitized_paths(self):
        identifier = str(uuid.uuid4())
        digest = hashlib.sha256(b"x").digest()
        duplicate = FileEntry(identifier, "one.bin", "application/octet-stream", 1, digest)
        with self.assertRaises(ValueError):
            validate_manifest(Manifest(str(uuid.uuid4()), (duplicate, duplicate)))
        unsafe = FileEntry(str(uuid.uuid4()), "../secret.bin", "application/octet-stream", 1, digest)
        with self.assertRaises(ValueError):
            validate_manifest(Manifest(str(uuid.uuid4()), (unsafe,)))

    def test_android_pairing_kdf_matches_pbkdf2_parameters(self):
        challenge = bytes(range(32))
        expected = hashlib.pbkdf2_hmac("sha256", b"123456789", challenge[:16], 210_000, dklen=32)
        self.assertEqual(expected, derive_v2_pairing_key("123456789", challenge))
        legacy = "ab" * 32
        self.assertEqual(bytes.fromhex(legacy), derive_v2_pairing_key(legacy, challenge))

    def test_modified_utf_round_trip_matches_java_write_utf_semantics(self):
        value = "peer-مالك-😀-\\x00"
        encoded = _java_write_utf(value)
        decoded, end = _java_read_utf(encoded, 0)
        self.assertEqual(value, decoded)
        self.assertEqual(len(encoded), end)

    def test_v2_offsets_are_bound_to_every_manifest_file(self):
        first = FileEntry(str(uuid.uuid4()), "a.bin", "application/octet-stream", 10, hashlib.sha256(b"a").digest())
        second = FileEntry(str(uuid.uuid4()), "b.bin", "application/octet-stream", 0, hashlib.sha256(b"").digest())
        manifest = Manifest(str(uuid.uuid4()), (first, second))
        encoded = _encode_v2_offsets(manifest, {first.file_id: 7, second.file_id: 0})
        self.assertEqual({first.file_id: 7, second.file_id: 0}, _read_v2_offsets(encoded, manifest))
        with self.assertRaises(ValueError):
            _read_v2_offsets(encoded[:-1], manifest)

    def test_v2_result_codec_preserves_per_file_status_and_arabic_error(self):
        first = FileEntry(str(uuid.uuid4()), "a.bin", "application/octet-stream", 10, hashlib.sha256(b"a").digest())
        second = FileEntry(str(uuid.uuid4()), "b.bin", "application/octet-stream", 4, hashlib.sha256(b"b").digest())
        manifest = Manifest(str(uuid.uuid4()), (first, second))
        results = [
            {"file_id": first.file_id, "status": STATUS_COMPLETED, "transferred": 10, "error": None},
            {"file_id": second.file_id, "status": STATUS_FAILED, "transferred": 2, "error": "انقطع الاتصال"},
        ]
        self.assertEqual(results, _read_v2_results(_encode_v2_results(manifest, results), manifest))

    def test_v2_aes_gcm_frame_round_trip_and_directional_nonce(self):
        import socket
        left, right = socket.socketpair()
        try:
            key = derive_v2_key(bytes(range(32)), bytes(range(32)), "client-to-server")
            prefix = b"abcd"
            payload = b"authenticated v2 payload"
            v2_write_frame(left, key, prefix, 9, payload)
            self.assertEqual(payload, v2_read_frame(right, key, prefix, 9, 1024))
            self.assertEqual(b"abcd" + (9).to_bytes(8, "big"), v2_nonce(prefix, 9))
        finally:
            left.close()
            right.close()

    def test_mpc2_control_authentication_and_aead_match_wire_contract(self):
        challenge = bytes(range(32))
        master = derive_v2_pairing_key("123456789", challenge)
        request_id = "0123456789abcdef0123456789abcdef"
        peer_name = "MalekPlay Desktop".encode("utf-8")
        payload = (
            _java_write_utf("MPULTRA_CONTROL_REQUEST")
            + (1).to_bytes(4, "big", signed=True)
            + challenge
            + _java_write_utf(request_id)
            + len(peer_name).to_bytes(2, "big")
            + peer_name
        )
        expected = hashlib.sha256
        import hmac
        expected_mac = hmac.new(
            _control_subkey(master, "request-authentication"), payload, expected
        ).digest()
        self.assertEqual(expected_mac, _control_request_mac(master, challenge, request_id, peer_name))
        nonce = bytes(range(12))
        encrypted = _control_encrypt(master, nonce, "ACCEPT|" + request_id + "|4d616c656b")
        self.assertEqual(
            "ACCEPT|" + request_id + "|4d616c656b",
            _control_decrypt(master, nonce, encrypted),
        )
        self.assertEqual(0x4D504332, CONTROL_MAGIC)
        self.assertEqual(1, CONTROL_VERSION)

    def test_control_channel_mpc2_handshake_and_heartbeat(self):
        import socket
        import threading
        import time
        from unittest.mock import patch

        probe = socket.socket()
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
        probe.close()
        received = []
        failures = []

        def server():
            try:
                with patch("ultra_share_v2._is_private_v2_peer", return_value=True):
                    serve_control_once(
                        port, "123456789", "Desktop Test",
                        lambda name, host: received.append((name, host)) or True,
                    )
            except Exception as exc:
                failures.append(exc)

        worker = threading.Thread(target=server, daemon=True)
        worker.start()
        time.sleep(0.15)
        with patch("ultra_share_v2._is_private_v2_peer", return_value=True):
            session = connect_control_session("127.0.0.1", port, "123456789")
            time.sleep(5.2)
            self.assertIsNone(session.error)
            session.close()
        worker.join(timeout=2)
        self.assertFalse(worker.is_alive())
        self.assertFalse(failures)
        self.assertEqual(1, len(received))
        self.assertEqual("MalekPlay Desktop", received[0][0])
        self.assertTrue(received[0][1])

    def test_android_v2_batch_sender_and_desktop_receiver_interoperate(self):
        import socket
        import tempfile
        import threading
        import time
        from pathlib import Path
        from unittest.mock import patch

        def free_port():
            probe = socket.socket()
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
            probe.close()
            return port

        data_port = free_port()
        control_port = free_port()
        while control_port == data_port:
            control_port = free_port()
        with tempfile.TemporaryDirectory() as source_dir, tempfile.TemporaryDirectory() as output_dir:
            source_root = Path(source_dir)
            first = source_root / "مقطع.bin"
            second = source_root / "empty.dat"
            first.write_bytes(b"ultra-share-v2" * 8192)
            second.write_bytes(b"")
            received = []
            failures = []

            def control_server():
                try:
                    with patch("ultra_share_v2._is_private_v2_peer", return_value=True):
                        serve_control_once(
                            control_port, "123456789", "Desktop Test",
                            lambda _name, _host: True,
                        )
                except Exception as exc:
                    failures.append(exc)

            control_worker = threading.Thread(target=control_server, daemon=True)
            control_worker.start()
            time.sleep(0.15)

            def receiver():
                try:
                    with patch("ultra_share_v2._is_private_v2_peer", return_value=True):
                        received.extend(receive_batch_v2(data_port, "123456789", Path(output_dir)))
                except Exception as exc:
                    failures.append(exc)

            worker = threading.Thread(target=receiver, daemon=True)
            worker.start()
            time.sleep(0.15)
            with patch("ultra_share_v2._is_private_v2_peer", return_value=True):
                control = connect_control_session("127.0.0.1", control_port, "123456789")
                try:
                    results = send_batch_v2(
                        "127.0.0.1", data_port, "123456789", [first, second]
                    )
                    self.assertIsNone(control.error)
                finally:
                    control.close()
            worker.join(timeout=5)
            control_worker.join(timeout=2)
            self.assertFalse(worker.is_alive())
            self.assertFalse(control_worker.is_alive())
            self.assertFalse(failures)
            self.assertEqual(2, len(results))
            self.assertTrue(all(item["status"] == STATUS_COMPLETED for item in results))
            self.assertEqual(2, len(received))
            restored = {Path(item["path"]).name: Path(item["path"]).read_bytes() for item in received}
            self.assertEqual(first.read_bytes(), restored["مقطع.bin"])
            self.assertEqual(b"", restored["empty.dat"])

    def test_rejects_invalid_mime_and_digest_length(self):
        for mime, digest in (("application/pdf;bad", bytes(32)), ("application/pdf", bytes(31))):
            entry = FileEntry(str(uuid.uuid4()), "x.bin", mime, 1, digest)
            with self.assertRaises(ValueError):
                validate_manifest(Manifest(str(uuid.uuid4()), (entry,)))


if __name__ == "__main__":
    unittest.main()
