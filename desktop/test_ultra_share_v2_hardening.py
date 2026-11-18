import hashlib
import tempfile
import unittest
import uuid
from pathlib import Path

from ultra_share_v2 import (
    MAX_BATCH_BYTES, FileEntry, Manifest, validate_manifest,
    _media_signature_matches,
)


class UltraShareV2HardeningTests(unittest.TestCase):
    def test_batch_total_is_bounded(self):
        digest = hashlib.sha256(b"x").digest()
        entries = (
            FileEntry(str(uuid.uuid4()), "a.mp4", "video/mp4", 11 * 1024**3, digest),
            FileEntry(str(uuid.uuid4()), "b.mp4", "video/mp4", 10 * 1024**3, digest),
        )
        with self.assertRaises(ValueError):
            validate_manifest(Manifest(str(uuid.uuid4()), entries))
        self.assertEqual(20 * 1024**3, MAX_BATCH_BYTES)

    def test_content_signature_rejects_mislabeled_payload(self):
        with tempfile.TemporaryDirectory() as folder:
            valid = Path(folder) / "valid.mp4"
            invalid = Path(folder) / "renamed.mp4"
            valid.write_bytes(bytes.fromhex("000000186674797069736f6d") + bytes(8))
            invalid.write_bytes(b"MZ-not-a-video")
            self.assertTrue(_media_signature_matches(valid, "valid.mp4"))
            self.assertFalse(_media_signature_matches(invalid, "renamed.mp4"))

    def test_signature_validation_fails_closed_for_unknown_extensions(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "sample.xyz"
            path.write_bytes(b"anything")
            self.assertFalse(_media_signature_matches(path, "sample.xyz"))


if __name__ == "__main__":
    unittest.main()
