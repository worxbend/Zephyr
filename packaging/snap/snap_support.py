"""Input and artifact checks for the native, prebuilt-image Snap pipeline."""
from pathlib import Path
import re
import shutil
import struct
import argparse
import os
import subprocess
import tempfile
import xml.etree.ElementTree as ET

import yaml

APP_ID = "com.worxbend.zephyr"
APP_PATH = Path("desktopApp/build/compose/binaries/main/app") / APP_ID
ELF_MACHINES = {"amd64": 62, "arm64": 183}


def check_elf(path: Path, arch: str) -> None:
    with path.open("rb") as stream:
        header = stream.read(20)
    if len(header) < 20 or header[:6] != b"\x7fELF\x02\x01":
        raise ValueError(f"Expected a 64-bit little-endian ELF file: {path}")
    machine = struct.unpack_from("<H", header, 18)[0]
    if machine != ELF_MACHINES[arch]:
        raise ValueError(f"ELF architecture mismatch: {path} has machine {machine}, expected {arch}")


def prepare(root: Path, destination: Path, arch: str) -> str:
    version = release_version(root)
    image = root / APP_PATH
    if not (image / "bin" / APP_ID).is_file():
        raise ValueError("Build :desktopApp:createDistributable first using Corretto 21 on the native Linux architecture")
    check_elf(image / "bin" / APP_ID, arch)
    check_elf(image / "lib/runtime/lib/server/libjvm.so", arch)
    state = ET.parse(image / "lib/app/.jpackage.xml").getroot()
    if state.attrib.get("platform") != "linux" or state.findtext("app-version") != version:
        raise ValueError("Prebuilt application version/platform does not match gradle.properties/Linux")
    for path in image.rglob("*"):
        if path.is_symlink() and not path.resolve().is_relative_to(image.resolve()):
            raise ValueError(f"Application symlink escapes image: {path}")
    shutil.copytree(image, destination / APP_PATH, symlinks=True)
    for relative in (Path("snap/snapcraft.yaml"), Path("gradle.properties")):
        (destination / relative).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(root / relative, destination / relative)
    shutil.copytree(root / "packaging/linux", destination / "packaging/linux")
    return version


def release_version(root: Path) -> str:
    values = re.findall(r"^zephyrVersion=(.+)$", (root / "gradle.properties").read_text(), re.M)
    if len(values) != 1 or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", values[0]):
        raise ValueError("gradle.properties must contain one numeric zephyrVersion=X.Y.Z")
    return values[0]


def check_metadata(metadata: dict, version: str, arch: str) -> None:
    expected = {"name": "zephyr", "version": version, "base": "core24",
                "grade": "stable", "confinement": "classic", "architectures": [arch]}
    for key, value in expected.items():
        if metadata.get(key) != value:
            raise ValueError(f"Snap metadata {key}: expected {value!r}, got {metadata.get(key)!r}")
    app = metadata.get("apps", {}).get("zephyr", {})
    if app.get("command") != f"usr/lib/zephyr/bin/{APP_ID}" or app.get("common-id") != APP_ID:
        raise ValueError("Snap application command/common-id mismatch")


def validate(artifact: Path, version: str, arch: str) -> None:
    scratch = Path(os.environ.get("TMPDIR", Path.home() / ".cache/zephyr"))
    scratch.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="zephyr-snap-check.", dir=scratch) as directory:
        root = Path(directory) / "unpacked"
        subprocess.run(["unsquashfs", "-quiet", "-no-progress", "-d", str(root), str(artifact)], check=True)
        check_metadata(yaml.safe_load((root / "meta/snap.yaml").read_text()), version, arch)
        image = root / "usr/lib/zephyr"
        for relative in (f"bin/{APP_ID}", "lib/runtime/lib/server/libjvm.so", "lib/runtime/lib/libawt_xawt.so"):
            check_elf(image / relative, arch)
        if not os.access(image / "bin" / APP_ID, os.X_OK):
            raise ValueError("Snap launcher is not executable")
        if not list((image / "lib/app").glob("desktopApp-*.jar")):
            raise ValueError("Application jar is missing")
        skiko = list((image / "lib/app").glob("libskiko-linux-*.so"))
        if len(skiko) != 1:
            raise ValueError("Expected one extracted native Skiko library")
        check_elf(skiko[0], arch)
        for relative in (f"usr/share/applications/{APP_ID}.desktop",
                         f"usr/share/icons/hicolor/scalable/apps/{APP_ID}.svg",
                         f"usr/share/metainfo/{APP_ID}.metainfo.xml", "meta/gui/icon.png",
                         "snap/manifest.yaml", "snap/snapcraft.yaml"):
            if not (root / relative).is_file():
                raise ValueError(f"Snap content missing: {relative}")
        font_config = ET.parse(root / "etc/fonts/fonts.conf").getroot()
        if not any(node.get("prefix") == "relative" and node.text == "../../usr/share/fonts"
                   for node in font_config.findall("dir")):
            raise ValueError("Fontconfig does not include the bundled fallback fonts")
        if not list((root / "usr/share/fonts").rglob("*.ttf")):
            raise ValueError("Bundled fallback fonts are missing")
        manifest = yaml.safe_load((root / "snap/manifest.yaml").read_text())
        packages = manifest.get("parts", {}).get("runtime-dependencies", {}).get("stage-packages", [])
        if not packages or not any(str(package).startswith("libx11-6=") for package in packages):
            raise ValueError("Snap build metadata is missing versioned stage-package dependencies")
        for library in ("libasound.so.2", "libfontconfig.so.1", "libfreetype.so.6", "libGL.so.1", "libX11.so.6"):
            # Only bundled libraries count, not the host's /usr/lib.
            if not any(path.is_file() for path in (root / "usr/lib").rglob(library)):
                raise ValueError(f"Required bundled dependency is missing: {library}")
    print(f"Validated zephyr {version} {arch}: classic/stable, runtime, native code, desktop assets, dependency manifest")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prepare_parser = commands.add_parser("prepare")
    prepare_parser.add_argument("root", type=Path)
    prepare_parser.add_argument("destination", type=Path)
    prepare_parser.add_argument("arch", choices=ELF_MACHINES)
    validate_parser = commands.add_parser("validate")
    validate_parser.add_argument("artifact", type=Path)
    validate_parser.add_argument("version")
    validate_parser.add_argument("arch", choices=ELF_MACHINES)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            print(prepare(args.root, args.destination, args.arch))
        else:
            validate(args.artifact, args.version, args.arch)
    except (ValueError, OSError, ET.ParseError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Snap validation failed: {error}\n")


if __name__ == "__main__":
    main()
