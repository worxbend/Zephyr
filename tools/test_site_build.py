import copy
import json
import os
from pathlib import Path
import tempfile
import unittest

from build_site import build, release_values, render

ROOT = Path(__file__).resolve().parents[1]


class SiteBuildTest(unittest.TestCase):
    def setUp(self):
        self.release = json.loads((ROOT / "site/release.json").read_text())

    def test_downloads_follow_published_release_not_gradle_source_version(self):
        values = release_values(self.release)
        self.assertEqual(values["VERSION"], self.release["tag_name"][1:])
        for arch in ("AMD64", "ARM64"):
            for fmt in ("APPIMAGE", "FLATPAK", "SNAP"):
                self.assertIn(f"/releases/download/{self.release['tag_name']}/", values[f"{fmt}_{arch}"])

    def test_drafts_and_prereleases_cannot_be_advertised(self):
        for key in ("draft", "prerelease"):
            for value in (True, None, "false"):
                with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                    release_values({**self.release, key: value})

    def test_version_and_release_url_must_match(self):
        for mutation in ({"tag_name": "v1.02.1"}, {"tag_name": "v1.2.1-rc1"}, {"html_url": "https://example.com/"}):
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                release_values({**self.release, **mutation})

    def test_release_tag_rejects_unicode_digits(self):
        changed = copy.deepcopy(self.release)
        original_version = self.release["tag_name"][1:]
        tag = self.release["tag_name"] + "\u0661"
        changed["tag_name"] = tag
        changed["html_url"] = f"https://github.com/worxbend/Zephyr/releases/tag/{tag}"
        for asset in changed["assets"]:
            asset["name"] = asset["name"].replace(original_version, tag[1:])
            asset["browser_download_url"] = f"https://github.com/worxbend/Zephyr/releases/download/{tag}/{asset['name']}"
        with self.assertRaises(ValueError):
            release_values(changed)

    def test_all_six_architecture_format_assets_and_checksums_are_required(self):
        for asset in self.release["assets"]:
            changed = {**self.release, "assets": [a for a in self.release["assets"] if a != asset]}
            with self.subTest(asset=asset["name"]), self.assertRaises(ValueError):
                release_values(changed)

    def test_duplicate_and_empty_assets_are_rejected(self):
        duplicate = {**self.release, "assets": self.release["assets"] + [self.release["assets"][0]]}
        with self.assertRaises(ValueError):
            release_values(duplicate)
        for size in (0, -1, True, "123"):
            changed = copy.deepcopy(self.release)
            changed["assets"][0]["size"] = size
            with self.subTest(size=size), self.assertRaises(ValueError):
                release_values(changed)

    def test_foreign_or_wrong_version_asset_links_are_rejected(self):
        for url in ("javascript:alert(1)", "https://example.com/package", "https://github.com/worxbend/Zephyr/releases/download/v99.0.0/SHA256SUMS"):
            changed = copy.deepcopy(self.release)
            changed["assets"][0]["browser_download_url"] = url
            with self.subTest(url=url), self.assertRaises(ValueError):
                release_values(changed)

    def test_render_escapes_values_and_rejects_unknown_placeholders(self):
        self.assertEqual(render('{{VERSION}}', {"VERSION": '<"&'}), '&lt;&quot;&amp;')
        for template in ('{{MISSING}}', '{{version}}'):
            with self.subTest(template=template), self.assertRaises(ValueError):
                render(template, {"VERSION": "1.2.3"})

    def make_source(self, root):
        (root / "site/assets").mkdir(parents=True)
        (root / "packaging/linux").mkdir(parents=True)
        (root / "site/index.html").write_text('<h1>{{VERSION}}</h1><a href="{{APPIMAGE_AMD64}}">Download</a>')
        for name in ("styles.css", "app.js"):
            (root / "site/assets" / name).write_text("fixture")
        (root / "packaging/linux/com.worxbend.zephyr.png").write_bytes(b"fixture-icon")
        for i in range(1, 4):
            (root / f"Screenshot-{i}.png").write_bytes(b"fixture-screen")
        (root / "site/package.json").write_text('{"private":true}')

    def test_only_public_assets_and_rendered_metadata_enter_artifact(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as tmp:
            root = Path(tmp)
            self.make_source(root)
            output = root / "build/site"
            build(root, output, self.release, "verified-sha")
            expected = {"index.html", ".nojekyll", "robots.txt", "sitemap.xml", "build-meta.json", "assets/styles.css", "assets/app.js", "assets/icon.png", *(f"assets/screenshot-{i}.png" for i in range(1, 4))}
            actual = {str(p.relative_to(output)) for p in output.rglob("*") if p.is_file()}
            self.assertEqual(actual, expected)
            self.assertNotIn("{{", (output / "index.html").read_text())
            self.assertEqual(json.loads((output / "build-meta.json").read_text())["source_sha"], "verified-sha")
            self.assertIn("https://worxbend.github.io/Zephyr/", (output / "sitemap.xml").read_text())

    def test_invalid_input_does_not_erase_previous_artifact(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as tmp:
            root = Path(tmp)
            self.make_source(root)
            output = root / "build/site"
            output.mkdir(parents=True)
            (output / "index.html").write_text("previous")
            with self.assertRaises(ValueError):
                build(root, output, {**self.release, "draft": True})
            self.assertEqual((output / "index.html").read_text(), "previous")
            (root / "Screenshot-3.png").unlink()
            with self.assertRaises(ValueError):
                build(root, output, self.release)
            self.assertEqual((output / "index.html").read_text(), "previous")

    def test_output_rejects_symlinked_build_root_and_nested_aliases(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as tmp:
            root = Path(tmp) / "repo"
            self.make_source(root)
            outside = Path(tmp) / "outside"
            previous = outside / "site/index.html"
            previous.parent.mkdir(parents=True)
            previous.write_text("external sentinel")
            build_root = root / "build"
            build_root.symlink_to(outside, target_is_directory=True)
            with self.assertRaises(ValueError):
                build(root, build_root / "site", self.release)
            self.assertEqual(previous.read_text(), "external sentinel")

            build_root.unlink()
            build_root.mkdir()
            # An in-tree alias must not redirect deletion to a different build artifact.
            protected = build_root / "other-artifact"
            protected.mkdir()
            sentinel = protected / "sentinel"
            sentinel.write_text("owned by another task")
            alias = build_root / "site"
            alias.symlink_to(protected, target_is_directory=True)
            with self.assertRaises(ValueError):
                build(root, alias, self.release)
            self.assertEqual(sentinel.read_text(), "owned by another task")
            with self.assertRaises(ValueError):
                build(root, alias / "nested", self.release)
            self.assertFalse((protected / "nested").exists())

    def test_output_cannot_replace_repository_or_source_directory(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as tmp:
            root = Path(tmp)
            self.make_source(root)
            for output in (root, root / "site", root / "build"):
                with self.subTest(output=output), self.assertRaises(ValueError):
                    build(root, output, self.release)
            self.assertTrue((root / "site/index.html").is_file())


if __name__ == "__main__":
    unittest.main()
