import hashlib
import socket
import struct
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from ultra_share_web import is_local_host_header, validate_upload

from ultra_share_pc import (
    DEFAULT_PORT,
    VERSION,
    build_transfer_id,
    derive_key,
    derive_pairing_key,
    encode_text,
    get_secret,
    find_completed_receipt,
    is_lan_ipv4,
    parse_metadata,
    read_frame,
    read_resume_marker,
    safe_name,
    send_file,
    send_files,
    write_frame,
)


class UltraShareDesktopProtocolTests(unittest.TestCase):
    def test_windows_and_relative_paths_are_reduced_to_safe_names(self):
        self.assertEqual("clip.mp4", safe_name(r"C:\\Users\\Guest\\clip.mp4"))
        self.assertEqual("song.mp3", safe_name("../../music/song.mp3"))

    def test_invalid_empty_names_are_rejected(self):
        with self.assertRaises(ValueError):
            safe_name("...")

    def test_android_transfer_identity_layout_is_reproduced(self):
        filename = "مقطع.mp4"
        size = 123456
        digest = hashlib.sha256(b"sample content").digest()
        name = filename.encode("utf-8")
        identity = struct.pack(">I", len(name)) + name + struct.pack(">q", size) + digest
        expected = hashlib.sha256(identity).digest()[:12].hex()
        self.assertEqual("0b9008e02c8c871dcedefd6a", expected)
        self.assertEqual(expected, build_transfer_id(filename, size, digest))

    def test_filename_sanitization_removes_format_and_c1_controls(self):
        self.assertEqual("clip.mp4", safe_name("clip\u200b.mp4"))
        self.assertEqual("song.mp3", safe_name("song\u0085.mp3"))

    def test_metadata_round_trip_matches_android_wire_layout(self):
        digest = hashlib.sha256(b"hello").digest()
        encoded = (
            struct.pack(">H", VERSION)
            + encode_text("clip.mp4", 768)
            + struct.pack(">q", 5)
            + encode_text("video/mp4", 192)
            + digest
        )
        self.assertEqual(
            ("clip.mp4", 5, "video/mp4", digest),
            parse_metadata(encoded),
        )

    def test_android_nine_digit_pin_uses_matching_pbkdf2_derivation(self):
        pin = "004219837"
        challenge = bytes(range(32))
        self.assertEqual(pin, get_secret(pin))
        self.assertEqual(
            "313f3930d3a5684ebdd21a38ea0be31fb255fe5436476972f2a387b2ded57150",
            derive_pairing_key(pin, challenge).hex(),
        )
        self.assertEqual(
            hashlib.pbkdf2_hmac("sha256", pin.encode("ascii"), challenge[:16], 210_000, dklen=32),
            derive_pairing_key(pin, challenge),
        )

    def test_legacy_hex_pairing_key_remains_supported(self):
        legacy = "ab" * 32
        self.assertEqual(legacy, get_secret(legacy))
        self.assertEqual(bytes.fromhex(legacy), derive_pairing_key(legacy, bytes(32)))

    def test_invalid_pairing_codes_are_rejected(self):
        for invalid in ("12345678901", "1234567890123", "not-a-pin"):
            with self.subTest(invalid=invalid):
                with self.assertRaises(ValueError):
                    get_secret(invalid)

    def test_verified_receipt_prevents_duplicate_receive(self):
        content = b"already received"
        digest = hashlib.sha256(content).digest()
        transfer_id = build_transfer_id("clip.mp4", len(content), digest)
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            destination = directory / "clip.mp4"
            destination.write_bytes(content)
            (directory / f".ultrashare-{transfer_id}.received").write_text(
                f"{len(content)}:{digest.hex()}:clip.mp4", encoding="utf-8"
            )
            self.assertEqual(
                destination,
                find_completed_receipt(directory, transfer_id, len(content), digest),
            )

    def test_invalid_receipt_is_removed_and_never_trusted(self):
        content = b"tampered file"
        expected_digest = hashlib.sha256(b"original file").digest()
        transfer_id = build_transfer_id("clip.mp4", len(content), expected_digest)
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "clip.mp4").write_bytes(content)
            receipt = directory / f".ultrashare-{transfer_id}.received"
            receipt.write_text(
                f"{len(content)}:{expected_digest.hex()}:clip.mp4", encoding="utf-8"
            )
            self.assertIsNone(
                find_completed_receipt(directory, transfer_id, len(content), expected_digest)
            )
            self.assertFalse(receipt.exists())

    def test_resume_marker_reader_recovers_from_unreadable_sidecar(self):
        with tempfile.TemporaryDirectory() as temporary:
            marker = Path(temporary) / "resume.meta"
            self.assertIsNone(read_resume_marker(marker))
            marker.write_text("4096:abc", encoding="utf-8")
            self.assertEqual("4096:abc", read_resume_marker(marker))
            with patch.object(Path, "read_text", side_effect=OSError("permission denied")):
                self.assertIsNone(read_resume_marker(marker))

    def test_batch_validates_all_sources_before_starting_any_transfer(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            first = directory / "first.bin"
            first.write_bytes(b"first")
            missing = directory / "missing.bin"
            with patch("ultra_share_pc.send_file") as send:
                with self.assertRaises(FileNotFoundError):
                    send_files("192.168.1.20", DEFAULT_PORT, "004219837", [first, missing])
            send.assert_not_called()

    def test_batch_sends_files_in_selected_order(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            first = directory / "first.bin"
            second = directory / "second.bin"
            first.write_bytes(b"first")
            second.write_bytes(b"second")
            with patch("ultra_share_pc.send_file") as send:
                send_files("192.168.1.20", DEFAULT_PORT, "004219837", [first, second])
            self.assertEqual(
                [first, second],
                [invocation.args[3] for invocation in send.call_args_list],
            )
            self.assertTrue(all(
                invocation.args[:3] == ("192.168.1.20", DEFAULT_PORT, "004219837")
                for invocation in send.call_args_list
            ))

    def test_pairing_rejection_is_not_retried(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "clip.mp4"
            source.write_bytes(b"sample")
            with patch("ultra_share_pc.send_once", side_effect=PermissionError("wrong PIN")) as send:
                with patch("ultra_share_pc.time.sleep") as sleep:
                    with self.assertRaises(PermissionError):
                        send_file("192.168.1.20", DEFAULT_PORT, "004219837", source)
            self.assertEqual(1, send.call_count)
            sleep.assert_not_called()

    def test_transient_connection_failure_is_retried(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "clip.mp4"
            source.write_bytes(b"sample")
            with patch(
                "ultra_share_pc.send_once",
                side_effect=[ConnectionResetError("connection reset"), None],
            ) as send:
                with patch("ultra_share_pc.time.sleep") as sleep:
                    send_file("192.168.1.20", DEFAULT_PORT, "004219837", source)
            self.assertEqual(2, send.call_count)
            sleep.assert_called_once_with(1)

    def test_lan_filter_allows_private_and_link_local_only(self):
        self.assertTrue(is_lan_ipv4("192.168.1.20"))
        self.assertTrue(is_lan_ipv4("169.254.10.2"))
        self.assertFalse(is_lan_ipv4("127.0.0.1"))
        self.assertFalse(is_lan_ipv4("8.8.8.8"))
        self.assertFalse(is_lan_ipv4("example.com"))

    def test_encrypted_frames_round_trip_with_directional_key(self):
        secret = bytes(range(32))
        challenge = bytes(range(32, 64))
        key = derive_key(secret, challenge, "client-to-server")
        prefix = b"MPU1"
        left, right = socket.socketpair()
        try:
            write_frame(left, key, prefix, 7, b"encrypted payload")
            self.assertEqual(
                b"encrypted payload",
                read_frame(right, key, prefix, 7, 128),
            )
        finally:
            left.close()
            right.close()

    def test_browser_ui_rejects_non_loopback_host_headers(self):
        self.assertTrue(is_local_host_header("127.0.0.1:8765", 8765))
        self.assertTrue(is_local_host_header("localhost:8765", 8765))
        self.assertFalse(is_local_host_header("192.168.1.10:8765", 8765))
        self.assertFalse(is_local_host_header("127.0.0.1.evil.test:8765", 8765))
        self.assertFalse(is_local_host_header("127.0.0.1:9000", 8765))
        self.assertFalse(is_local_host_header("user@127.0.0.1:8765", 8765))

    def test_browser_upload_validates_private_host_pin_filename_and_media_type(self):
        self.assertEqual(
            ("192.168.1.20", "004219837", "مقطع.mp4"),
            validate_upload("192.168.1.20", "004219837", "مقطع.mp4", 128, "video/mp4"),
        )
        self.assertEqual(
            ("192.168.1.20", "004219837", "cover.png"),
            validate_upload("192.168.1.20", "004219837", "cover.png", 128, "image/png"),
        )
        with self.assertRaises(ValueError):
            validate_upload("192.168.1.20", "004219837", "installer.apk", 128, "application/vnd.android.package-archive")
        with self.assertRaises(ValueError):
            validate_upload("192.168.1.20", "004219837", "notes.pdf", 128, "application/pdf")
        with self.assertRaises(ValueError):
            validate_upload("8.8.8.8", "004219837", "clip.mp4", 128)
        with self.assertRaises(ValueError):
            validate_upload("192.168.1.20", "bad-pin", "clip.mp4", 128)
        with self.assertRaises(ValueError):
            validate_upload("192.168.1.20", "004219837", "clip.mp4", -1)


if __name__ == "__main__":
    unittest.main()
