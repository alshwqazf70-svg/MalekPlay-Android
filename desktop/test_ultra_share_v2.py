import hashlib
import unittest
import uuid

from ultra_share_v2 import (
    FileEntry,
    Manifest,
    decode_manifest,
    encode_manifest,
    validate_manifest,
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

    def test_rejects_invalid_mime_and_digest_length(self):
        for mime, digest in (("application/pdf;bad", bytes(32)), ("application/pdf", bytes(31))):
            entry = FileEntry(str(uuid.uuid4()), "x.bin", mime, 1, digest)
            with self.assertRaises(ValueError):
                validate_manifest(Manifest(str(uuid.uuid4()), (entry,)))


if __name__ == "__main__":
    unittest.main()
