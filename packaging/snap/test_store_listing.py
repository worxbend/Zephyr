"""Offline checks for the Snap Store copy and reviewed listing media."""
import hashlib
import json
from pathlib import Path
import struct
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]


class StoreListingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.recipe = yaml.safe_load((ROOT / "snap/snapcraft.yaml").read_text())
        cls.media = json.loads((ROOT / "snap/listing/media.json").read_text())

    def test_identity_license_and_summary(self):
        self.assertEqual(self.recipe["name"], "zephyr")
        self.assertEqual(self.recipe["title"], "Zephyr")
        self.assertEqual(self.recipe["license"], "MIT")
        summary = self.recipe["summary"]
        self.assertTrue(0 < len(summary) < 80)
        self.assertNotIn("\n", summary)
        self.assertIn("SDKMAN", summary)

    def test_project_and_support_links_use_canonical_repository(self):
        project = "https://github.com/worxbend/Zephyr"
        for key in ("website", "source-code"):
            with self.subTest(key=key):
                self.assertEqual(self.recipe[key], project)
        for key in ("issues", "contact"):
            with self.subTest(key=key):
                self.assertEqual(self.recipe[key], project + "/issues")

    def test_description_explains_setup_and_permissions(self):
        description = self.recipe["description"]
        for required in ("https://sdkman.io/install", "SDKMAN_DIR", "~/.sdkman",
                         "runtime is included", "Internet access is required",
                         "classic confinement", "not restricted",
                         "supported terminal emulator"):
            with self.subTest(required=required):
                self.assertIn(required, description)
        self.assertEqual(self.recipe["confinement"], "classic")

    def test_listing_media_matches_reviewed_manifest(self):
        screenshots = self.media["screenshots"]
        self.assertTrue(1 <= len(screenshots) <= 5)
        entries = [self.media["icon"], *screenshots]
        filenames = [entry["file"] for entry in entries]
        self.assertEqual(len(filenames), len(set(filenames)))
        self.assertEqual(set(filenames), {p.name for p in (ROOT / "snap/listing").glob("*.png")})
        for entry in entries:
            with self.subTest(file=entry["file"]):
                self.assertEqual(Path(entry["file"]).name, entry["file"])
                data = (ROOT / "snap/listing" / entry["file"]).read_bytes()
                self.assertEqual(data[:8], b"\x89PNG\r\n\x1a\n")
                self.assertEqual(data[12:16], b"IHDR")
                dimensions = struct.unpack(">II", data[16:24])
                self.assertEqual(dimensions, (entry["width"], entry["height"]))
                self.assertEqual(hashlib.sha256(data).hexdigest(), entry["sha256"])
        self.assertEqual((self.media["icon"]["width"], self.media["icon"]["height"]), (480, 480))
        for entry in screenshots:
            self.assertEqual((entry["width"], entry["height"]), (1280, 820))
            self.assertTrue(entry["caption"].strip())
            self.assertTrue(entry["alt"].strip())
        self.assertTrue((ROOT / self.recipe["icon"]).is_file())


if __name__ == "__main__":
    unittest.main()
