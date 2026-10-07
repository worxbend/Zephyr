"""Offline regression tests; run with /usr/bin/python3 -m unittest discover -s packaging/snap."""
import importlib.util
from pathlib import Path
import tempfile
import unittest


class SnapInputsTest(unittest.TestCase):
    def test_version_comes_from_gradle_properties(self):
        helper = Path(__file__).with_name("snap_support.py")
        self.assertTrue(helper.is_file(), "Snap input validation is not implemented")
        spec = importlib.util.spec_from_file_location("snap_support", helper)
        assert spec is not None and spec.loader is not None
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "gradle.properties").write_text("# Release\nzephyrVersion=1.1.0\n")
            self.assertEqual(module.release_version(root), "1.1.0")

    def test_preparation_rejects_missing_prebuilt_image(self):
        import snap_support
        self.assertTrue(hasattr(snap_support, "prepare"), "prebuilt-image preparation missing")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "gradle.properties").write_text("zephyrVersion=1.1.0\n")
            with self.assertRaisesRegex(ValueError, "createDistributable"):
                snap_support.prepare(root, root / "stage", "amd64")

    def test_build_entrypoint_documents_prerequisites(self):
        import subprocess
        script = Path(__file__).with_name("build-snap.sh")
        result = subprocess.run(["bash", str(script), "--help"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Corretto 21", result.stdout)
        self.assertIn("Docker", result.stdout)

    def test_metadata_rejects_wrong_identity(self):
        import snap_support
        self.assertTrue(hasattr(snap_support, "check_metadata"), "artifact metadata validation missing")
        with self.assertRaisesRegex(ValueError, "name"):
            snap_support.check_metadata({"name": "other"}, "1.1.0", "amd64")

    def test_registered_snap_name_and_desktop_command(self):
        import yaml
        recipe = yaml.safe_load((Path(__file__).resolve().parents[2] / "snap/snapcraft.yaml").read_text())
        self.assertEqual(recipe["name"], "zephyr")
        self.assertIn("zephyr", recipe["apps"])
        self.assertIn("Exec=zephyr/", recipe["parts"]["desktop-integration"]["override-build"])

    def test_dump_keeps_jpackage_directory_layout(self):
        import yaml
        recipe = yaml.safe_load((Path(__file__).resolve().parents[2] / "snap/snapcraft.yaml").read_text())
        self.assertEqual(recipe["parts"]["zephyr"]["organize"], {
            "bin": "usr/lib/zephyr/bin", "lib": "usr/lib/zephyr/lib"
        })

    def test_mesa_llvm_indirect_dependencies_are_patched(self):
        import yaml
        recipe = yaml.safe_load((Path(__file__).resolve().parents[2] / "snap/snapcraft.yaml").read_text())
        self.assertIn("libedit2", recipe["parts"]["runtime-dependencies"]["stage-packages"])

    def test_fontconfig_includes_bundled_fonts(self):
        import yaml
        recipe = yaml.safe_load((Path(__file__).resolve().parents[2] / "snap/snapcraft.yaml").read_text())
        self.assertIn('prefix="relative">../../usr/share/fonts',
                      recipe["parts"]["runtime-dependencies"].get("override-build", ""))

    def test_versions_reject_duplicate_or_unsafe_values(self):
        import snap_support
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for text in ("", "zephyrVersion=../escape\n", "zephyrVersion=1.1.0\nzephyrVersion=1.2.0\n"):
                with self.subTest(text=text):
                    (root / "gradle.properties").write_text(text)
                    with self.assertRaises(ValueError):
                        snap_support.release_version(root)

    def test_elf_machine_validation_for_both_native_architectures(self):
        import snap_support
        import struct
        with tempfile.TemporaryDirectory() as directory:
            binary = Path(directory) / "test-elf-header"
            for arch, machine in (("amd64", 62), ("arm64", 183)):
                with self.subTest(arch=arch):
                    header = bytearray(20)
                    header[:6] = b"\x7fELF\x02\x01"
                    struct.pack_into("<H", header, 18, machine)
                    binary.write_bytes(header)
                    snap_support.check_elf(binary, arch)
                    other = "arm64" if arch == "amd64" else "amd64"
                    with self.assertRaisesRegex(ValueError, "architecture mismatch"):
                        snap_support.check_elf(binary, other)
            binary.write_text("not an ELF")
            with self.assertRaisesRegex(ValueError, "ELF"):
                snap_support.check_elf(binary, "amd64")

    def test_metadata_validates_all_release_contract_fields(self):
        import snap_support
        for arch in ("amd64", "arm64"):
            metadata = {"name": "zephyr", "version": "1.1.0", "base": "core24",
                        "grade": "stable", "confinement": "classic", "architectures": [arch],
                        "apps": {"zephyr": {"command": "usr/lib/zephyr/bin/com.worxbend.zephyr",
                                             "common-id": "com.worxbend.zephyr"}}}
            snap_support.check_metadata(metadata, "1.1.0", arch)
            for key in metadata:
                with self.subTest(arch=arch, key=key):
                    bad = dict(metadata)
                    bad.pop(key)
                    with self.assertRaises(ValueError):
                        snap_support.check_metadata(bad, "1.1.0", arch)


if __name__ == "__main__":
    unittest.main()
