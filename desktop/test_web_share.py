import tempfile
import unittest
from pathlib import Path

from web_share import list_received_files, resolve_download_file


class BrowserSharePathTests(unittest.TestCase):
    def test_download_resolves_only_direct_children(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "Received"
            root.mkdir()
            inside = root / "video.mp4"
            inside.write_bytes(b"media")
            outside = Path(temporary) / "private.txt"
            outside.write_text("secret", encoding="utf-8")

            self.assertEqual(inside.resolve(), resolve_download_file(root, "video.mp4"))
            self.assertIsNone(resolve_download_file(root, "../private.txt"))
            self.assertNotEqual(outside.resolve(), resolve_download_file(root, "../private.txt"))

    def test_hidden_partial_files_are_not_listed_or_downloadable(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            visible = root / "received.bin"
            visible.write_bytes(b"ok")
            hidden = root / ".partial"
            hidden.write_bytes(b"partial")

            self.assertEqual(["received.bin"], [item["name"] for item in list_received_files(root)])
            self.assertIsNone(resolve_download_file(root, ".partial"))

    def test_symlink_to_outside_file_is_not_exposed(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = base / "Received"
            root.mkdir()
            outside = base / "outside.bin"
            outside.write_bytes(b"private")
            link = root / "shared.bin"
            try:
                link.symlink_to(outside)
            except (OSError, NotImplementedError):
                self.skipTest("Symlinks are not available on this platform")

            self.assertEqual([], list_received_files(root))
            self.assertIsNone(resolve_download_file(root, "shared.bin"))


if __name__ == "__main__":
    unittest.main()
