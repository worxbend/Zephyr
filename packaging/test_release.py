"""Unit fixtures exercise rejection paths; real packages are inspected in CI."""
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

import release
from verify_snap_store import guard_no_downgrade, stable_revision


class ReleaseContractsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def source(self):
        (self.root / "gradle.properties").write_text("zephyrVersion=1.2.3\n")
        (self.root / "snap").mkdir()
        (self.root / "snap/snapcraft.yaml").write_text("adopt-info: zephyr\n")
        path = self.root / f"packaging/linux/{release.APP_ID}.metainfo.xml"
        path.parent.mkdir(parents=True)
        path.write_text('<component><releases><release version="1.2.3"/></releases></component>')
        return path

    def matrix(self):
        for arch in release.ARCHES:
            for kind in release.FORMATS:
                (self.root / f"Zephyr-1.2.3-linux-{arch}.{kind}").write_bytes(b"test fixture, not a real package")

    def test_tag_and_source_version_agree(self):
        self.source()
        self.assertEqual(release.source_version(self.root, "v1.2.3"), "1.2.3")
        for tag in ("v1.2.4", "1.2.3", "v1.2.3\n", "v1.2.3-rc1"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                release.source_version(self.root, tag)

    def test_latest_appstream_version_must_agree(self):
        path = self.source()
        path.write_text('<component><releases><release version="1.2.2"/></releases></component>')
        with self.assertRaises(ValueError):
            release.source_version(self.root)

    def test_rejects_malformed_versions(self):
        for value in ("", "1.2", "01.2.3", "1.2.3-rc1", "1.2.3\n", "1.2.3;echo hacked", "../1.2.3"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                release.version_value(value)

    def test_matrix_requires_exactly_six_linux_packages(self):
        self.matrix()
        self.assertEqual(len(release.release_files(self.root, "1.2.3")), 6)
        (self.root / "Zephyr-1.2.3-linux-arm64.snap").unlink()
        with self.assertRaisesRegex(ValueError, "Incomplete release"):
            release.release_files(self.root, "1.2.3")

    def test_matrix_rejects_extra_artifact_or_wrong_version(self):
        self.matrix()
        for name in ("Zephyr-1.2.3.dmg", "Zephyr-1.2.2-linux-amd64.snap"):
            path = self.root / name
            path.write_bytes(b"fixture")
            with self.assertRaisesRegex(ValueError, "Unexpected release files"):
                release.release_files(self.root, "1.2.3")
            path.unlink()

    def test_matrix_rejects_empty_and_symlinked_packages(self):
        self.matrix()
        path = self.root / "Zephyr-1.2.3-linux-amd64.snap"
        path.write_bytes(b"")
        with self.assertRaises(ValueError):
            release.release_files(self.root, "1.2.3")
        path.unlink()
        path.symlink_to(self.root / "Zephyr-1.2.3-linux-arm64.snap")
        with self.assertRaises(ValueError):
            release.release_files(self.root, "1.2.3")

    def test_actual_elf_architecture_not_just_filename(self):
        for arch, (machine, _) in release.ARCHES.items():
            header = bytearray(64)
            header[:6] = b"\x7fELF\x02\x01"
            struct.pack_into("<H", header, 18, machine)
            release.elf_arch(header, arch)
            other = "arm64" if arch == "amd64" else "amd64"
            with self.assertRaises(ValueError):
                release.elf_arch(header, other)
        with self.assertRaises(ValueError):
            release.elf_arch(b"not an ELF executable", "amd64")

    def test_checksums_reject_missing_duplicate_or_changed_bytes(self):
        path = self.root / "example.snap"
        path.write_bytes(b"test fixture")
        sums = self.root / "SHA256SUMS"
        line = f"{release.checksum(path)}  example.snap\n"
        sums.write_text(line)
        release.verify_checksum(path, sums)
        for contents in ("", line + line, "bad  example.snap\n"):
            sums.write_text(contents)
            with self.assertRaises(ValueError):
                release.verify_checksum(path, sums)
        sums.write_text(line)
        path.write_bytes(b"changed fixture")
        with self.assertRaises(ValueError):
            release.verify_checksum(path, sums)

    def test_bundled_jvm_and_application_version_must_match(self):
        header = bytearray(64)
        header[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<H", header, 18, 62)
        files = {
            "app/bin/com.worxbend.zephyr": bytes(header),
            "app/lib/runtime/lib/server/libjvm.so": bytes(header),
            "app/lib/app/com.worxbend.zephyr.cfg": b"java-options=-Djpackage.app-version=1.2.3\n",
        }
        release.application_image(files.__getitem__, "app", "1.2.3", "amd64")
        with self.assertRaisesRegex(ValueError, "version mismatch"):
            release.application_image(files.__getitem__, "app", "1.2.4", "amd64")
        struct.pack_into("<H", header, 18, 183)
        files["app/lib/runtime/lib/server/libjvm.so"] = bytes(header)
        with self.assertRaisesRegex(ValueError, "ELF architecture"):
            release.application_image(files.__getitem__, "app", "1.2.3", "amd64")

    def test_manifest_is_not_written_when_deep_validation_fails(self):
        self.matrix()
        with patch.object(release, "validate_artifact", side_effect=ValueError("invalid package")):
            with self.assertRaises(ValueError):
                release.write_manifest(self.root, "1.2.3")
        self.assertFalse((self.root / "SHA256SUMS").exists())

    def test_flatpak_repository_is_initialized_before_import(self):
        with patch.object(release, "output", side_effect=[b"", ValueError("stop at import")]) as command:
            with self.assertRaisesRegex(ValueError, "stop at import"):
                release.validate_flatpak(self.root / "fixture.flatpak", "1.2.3", "amd64")
        init, bundle_import = [call.args for call in command.call_args_list]
        self.assertEqual(init[0], "ostree")
        self.assertEqual(init[2:], ("init", "--mode=archive-z2"))
        self.assertEqual(bundle_import[:2], ("flatpak", "build-import-bundle"))
        self.assertEqual(init[1], f"--repo={bundle_import[2]}")

    def test_store_readback_requires_exact_channel_arch_version_and_bytes(self):
        entry = {"version": "1.2.3", "revision": 42, "download": {"sha3-384": "fixture-digest"},
                 "channel": {"architecture": "arm64", "track": "latest", "risk": "stable"}}
        data = {"channel-map": [entry]}
        self.assertEqual(stable_revision(data, "1.2.3", "arm64", "fixture-digest"), 42)
        for version, arch, digest in (("1.2.2", "arm64", "fixture-digest"), ("1.2.3", "amd64", "fixture-digest"), ("1.2.3", "arm64", "different")):
            self.assertIsNone(stable_revision(data, version, arch, digest))
        entry["channel"]["risk"] = "edge"
        self.assertIsNone(stable_revision(data, "1.2.3", "arm64", "fixture-digest"))

    def test_store_downgrade_guard_checks_both_architectures_numerically(self):
        data = {"channel-map": [
            {"version": "1.9.0", "channel": {"architecture": "amd64", "track": "latest", "risk": "stable"}},
            {"version": "1.10.0", "channel": {"architecture": "arm64", "track": "latest", "risk": "stable"}},
        ]}
        guard_no_downgrade(data, "1.10.0")
        guard_no_downgrade(data, "2.0.0")
        with self.assertRaisesRegex(ValueError, "downgrade"):
            guard_no_downgrade(data, "1.9.0")
        data["channel-map"][1]["version"] = "invalid"
        with self.assertRaises(ValueError):
            guard_no_downgrade(data, "2.0.0")
        guard_no_downgrade({"channel-map": []}, "1.0.0")


if __name__ == "__main__":
    unittest.main()
