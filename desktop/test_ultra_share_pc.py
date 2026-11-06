import hashlib
import socket
import struct
import unittest

from ultra_share_pc import (
    VERSION,
    derive_key,
    encode_text,
    is_lan_ipv4,
    parse_metadata,
    read_frame,
    safe_name,
    write_frame,
)


class UltraShareDesktopProtocolTests(unittest.TestCase):
    def test_windows_and_relative_paths_are_reduced_to_safe_names(self):
        self.assertEqual("clip.mp4", safe_name(r"C:\\Users\\Guest\\clip.mp4"))
        self.assertEqual("song.mp3", safe_name("../../music/song.mp3"))

    def test_invalid_empty_names_are_rejected(self):
        with self.assertRaises(ValueError):
            safe_name("...")

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


if __name__ == "__main__":
    unittest.main()
