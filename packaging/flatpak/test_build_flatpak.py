#!/usr/bin/env python3
"""Offline argument/preflight tests; fake tools are not bundle build evidence.

Run: python3 -B -m unittest discover -s packaging/flatpak -p 'test_*.py' -v
All temporary files use TMPDIR; no Gradle, remotes or installations are touched.
"""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("build-flatpak.sh")
RUNTIME_REPO = "https://dl.flathub.org/repo/flathub.flatpakrepo"


class FlatpakBuildTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="zephyr-flatpak-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "project with spaces"
        self.script = self.root / "packaging/flatpak/build-flatpak.sh"
        self.script.parent.mkdir(parents=True)
        shutil.copy2(SCRIPT, self.script)
        (self.root / "gradle.properties").write_text("zephyrVersion=1.1.0\n")
        self.tools = Path(self.temp.name) / "tools"
        self.tools.mkdir()
        self.log = Path(self.temp.name) / "calls"
        self.env = dict(os.environ, PATH=f"{self.tools}:{os.environ['PATH']}",
                        ZEPHYR_SKIP_GRADLE_BUILD="1", CALLS=str(self.log),
                        TEST_ARCH="x86_64", TEST_OS="Linux")
        self.env.pop("ZEPHYR_VERSION", None)
        self.tool("uname", 'if [[ $1 == -m ]]; then printf "%s\\n" "$TEST_ARCH"; else printf "%s\\n" "$TEST_OS"; fi\n')
        self.tool("flatpak-builder", 'printf "builder %s\\n" "$*" >> "$CALLS"\nexit "${BUILDER_EXIT:-0}"\n')
        self.tool("flatpak", '''printf 'flatpak %s\\n' "$*" >> "$CALLS"
if [[ $1 == build-bundle ]]; then
    [[ ${EMPTY_BUNDLE:-0} != 1 ]] || exit 0
    for arg in "$@"; do
        if [[ $arg == *.flatpak ]]; then printf 'TEST FIXTURE, NOT A REAL BUNDLE\\n' > "$arg"; fi
    done
fi
''')
        self.image = self.root / "desktopApp/build/compose/binaries/main/app/com.worxbend.zephyr"
        self.launcher = self.image / "bin/com.worxbend.zephyr"
        self.jvm = self.image / "lib/runtime/lib/server/libjvm.so"
        self.binary(self.launcher, 62)
        self.binary(self.jvm, 62)
        self.config = self.image / "lib/app/com.worxbend.zephyr.cfg"
        self.config.parent.mkdir(parents=True)
        self.config.write_text("[JavaOptions]\njava-options=-Djpackage.app-version=1.1.0\n")
        gradle = self.root / "gradlew"
        gradle.write_text('#!/usr/bin/env bash\nprintf "GRADLE SHOULD NOT RUN\\n" >> "$CALLS"\nexit 99\n')
        gradle.chmod(0o755)

    def tool(self, name, body):
        path = self.tools / name
        path.write_text("#!/usr/bin/env bash\nset -euo pipefail\n" + body)
        path.chmod(0o755)

    @staticmethod
    def binary(path, machine):
        path.parent.mkdir(parents=True, exist_ok=True)
        header = bytearray(64)
        header[:6] = b"\x7fELF\x02\x01"
        header[18:20] = machine.to_bytes(2, "little")
        path.write_bytes(header)
        path.chmod(0o755)

    def run_build(self):
        return subprocess.run(["bash", str(self.script)], env=self.env,
                              cwd=self.temp.name, text=True, capture_output=True, check=False)

    def reject(self, expected):
        result = self.run_build()
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn(expected, result.stderr)
        self.assertFalse(self.log.exists(), "Preflight failures must not touch Flatpak or Gradle")

    def test_amd64_bundle_contract(self):
        result = self.run_build()
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.log.read_text()
        self.assertIn("--arch=x86_64", calls)
        self.assertIn(f"--runtime-repo={RUNTIME_REPO}", calls)
        self.assertIn("--assumeyes", calls)
        self.assertIn("--state-dir=", calls)
        self.assertIn("com.worxbend.zephyr master", calls)
        self.assertNotIn("GRADLE", calls)
        self.assertTrue((self.root / "dist/Zephyr-1.1.0-linux-amd64.flatpak").is_file())

    def test_arm64_bundle_contract(self):
        for arch in ("aarch64", "arm64"):
            with self.subTest(arch=arch):
                self.env["TEST_ARCH"] = arch
                self.binary(self.launcher, 183)
                self.binary(self.jvm, 183)
                result = self.run_build()
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("--arch=aarch64", self.log.read_text())
                self.assertTrue((self.root / "dist/Zephyr-1.1.0-linux-arm64.flatpak").is_file())

    def test_rejects_version_path_and_empty_version(self):
        for version in ("", "../1.1.0", "v1.1.0", "1.1.0\n1.2.0"):
            with self.subTest(version=version):
                self.env["ZEPHYR_VERSION"] = version
                self.reject("numeric major.minor.patch")

    def test_rejects_stale_image_version(self):
        self.env["ZEPHYR_VERSION"] = "1.2.0"
        self.reject("does not match requested")

    def test_rejects_foreign_launcher(self):
        self.binary(self.launcher, 183)
        self.reject("architecture does not match")

    def test_rejects_foreign_jvm(self):
        self.binary(self.jvm, 183)
        self.reject("architecture does not match")

    def test_rejects_missing_launcher(self):
        self.launcher.unlink()
        self.reject("missing or not executable")

    def test_rejects_non_elf_image(self):
        self.launcher.write_text("not an executable\n")
        self.reject("Not a little-endian ELF64")

    def test_rejects_missing_configuration(self):
        self.config.unlink()
        self.reject("configuration is missing")

    def test_rejects_unsupported_host(self):
        self.env["TEST_ARCH"] = "riscv64"
        self.reject("Unsupported Flatpak architecture")

    def test_rejects_non_linux(self):
        self.env["TEST_OS"] = "Darwin"
        self.reject("Native Linux builds are required")

    def test_rejects_invalid_skip_flag(self):
        self.env["ZEPHYR_SKIP_GRADLE_BUILD"] = "yes"
        self.reject("must be 0 or 1")

    def test_builder_failure_does_not_export_bundle(self):
        self.env["BUILDER_EXIT"] = "7"
        result = self.run_build()
        self.assertEqual(result.returncode, 7)
        self.assertNotIn("build-bundle", self.log.read_text())
        self.assertEqual(list((self.root / "dist").glob("*.flatpak")), [])

    def test_missing_bundle_is_failure(self):
        self.env["EMPTY_BUNDLE"] = "1"
        result = self.run_build()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("did not produce a nonempty bundle", result.stderr)
        self.assertEqual(list((self.root / "dist").glob("*.flatpak")), [])


if __name__ == "__main__":
    unittest.main()
