#!/usr/bin/env python3
"""Fail-closed validation for Zephyr's native Linux release artifacts (stdlib only)."""
import argparse
import configparser
import hashlib
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import xml.etree.ElementTree as ET

APP_ID = "com.worxbend.zephyr"
ARCHES = {"amd64": (62, "x86_64"), "arm64": (183, "aarch64")}
FORMATS = ("AppImage", "flatpak", "snap")
VERSION = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def version_value(value):
    require(VERSION.fullmatch(value) is not None, f"Expected a stable numeric version, got {value!r}")
    return value


def scalar(text, key):
    """Read a unique plain/quoted top-level scalar from generated Snap metadata."""
    values = re.findall(rf"^{re.escape(key)}: *([^\n]+)$", text, re.MULTILINE)
    require(len(values) == 1, f"Expected exactly one {key} field")
    return values[0].strip().strip("\"'")


def source_version(root, tag=None):
    values = re.findall(r"^zephyrVersion=(.+)$", (root / "gradle.properties").read_text(), re.MULTILINE)
    require(len(values) == 1, "Expected one zephyrVersion")
    version = version_value(values[0])
    snap = (root / "snap/snapcraft.yaml").read_text()
    require(scalar(snap, "adopt-info") == "zephyr", "Snap must adopt the Gradle version from the zephyr part")
    releases = ET.parse(root / f"packaging/linux/{APP_ID}.metainfo.xml").getroot().findall("releases/release")
    require(bool(releases) and releases[0].get("version") == version, "Latest AppStream release differs from Gradle")
    if tag is not None:
        require(tag == f"v{version}", f"Tag {tag!r} must match v{version}")
    return version


def elf_arch(header, arch):
    require(arch in ARCHES, f"Unsupported architecture: {arch}")
    require(len(header) >= 64 and header[:6] == b"\x7fELF\x02\x01", "Expected a little-endian ELF64 executable")
    require(struct.unpack_from("<H", header, 18)[0] == ARCHES[arch][0], f"ELF architecture is not {arch}")


def output(*args):
    return subprocess.check_output(args, stderr=subprocess.PIPE)


def appstream_version(data, version):
    component = ET.fromstring(data)
    require(component.findtext("id") == APP_ID, "AppStream application ID mismatch")
    release = component.find("releases/release")
    require(release is not None and release.get("version") == version, "Packaged AppStream version mismatch")


def application_image(cat, prefix, version, arch):
    elf_arch(cat(f"{prefix}/bin/{APP_ID}"), arch)
    elf_arch(cat(f"{prefix}/lib/runtime/lib/server/libjvm.so"), arch)
    config = cat(f"{prefix}/lib/app/{APP_ID}.cfg").decode()
    versions = re.findall(r"^java-options=-Djpackage\.app-version=(.+)$", config, re.MULTILINE)
    require(versions == [version], "Bundled application version mismatch")


def validate_snap(path, version, arch):
    metadata = output("unsquashfs", "-cat", str(path), "meta/snap.yaml").decode()
    for key, expected in {"name": "zephyr", "version": version, "confinement": "classic", "grade": "stable"}.items():
        require(scalar(metadata, key) == expected, f"Unexpected Snap {key}")
    lines = metadata.splitlines()
    require(lines.count("architectures:") == 1, "Expected one Snap architectures list")
    declared = []
    for line in lines[lines.index("architectures:") + 1:]:
        match = re.fullmatch(r"\s*-\s*(amd64|arm64)\s*", line)
        if not match:
            break
        declared.append(match[1])
    require(declared == [arch], "Snap architecture metadata mismatch")
    require(re.search(r"^\s+command: usr/lib/zephyr/bin/com\.worxbend\.zephyr\s*$", metadata, re.MULTILINE), "Snap command mismatch")
    application_image(lambda name: output("unsquashfs", "-cat", str(path), name), "usr/lib/zephyr", version, arch)
    for desktop in ("meta/gui/zephyr.desktop", f"usr/share/applications/{APP_ID}.desktop"):
        require(b"[Desktop Entry]" in output("unsquashfs", "-cat", str(path), desktop), f"Missing Snap desktop entry {desktop}")
    appstream_version(output("unsquashfs", "-cat", str(path), f"usr/share/metainfo/{APP_ID}.metainfo.xml"), version)


def validate_appimage(path, version, arch):
    with path.open("rb") as stream:
        header = stream.read(64)
        elf_arch(header, arch)
        require(header[8:11] == b"AI\x02", "Expected a type-2 AppImage")
        section_offset = struct.unpack_from("<Q", header, 40)[0]
        section_size, section_count = struct.unpack_from("<HH", header, 58)
        require(section_count > 0 and section_size > 0, "Missing AppImage ELF sections")
        offset = section_offset + section_size * section_count
        require(64 < offset < path.stat().st_size, "Invalid AppImage filesystem offset")
        stream.seek(offset)
        require(stream.read(4) == b"hsqs", "Missing AppImage SquashFS payload")
    def cat(name):
        return output("unsquashfs", "-offset", str(offset), "-cat", str(path), name)
    require(cat("AppRun").startswith(b"#!/"), "Missing AppRun launcher")
    application_image(cat, "usr/lib/zephyr", version, arch)
    appstream_version(cat(f"usr/share/metainfo/{APP_ID}.metainfo.xml"), version)


def validate_flatpak(path, version, arch):
    with tempfile.TemporaryDirectory(prefix="zephyr-flatpak-check-") as scratch:
        repo = Path(scratch) / "repo"
        output("ostree", f"--repo={repo}", "init", "--mode=archive-z2")
        output("flatpak", "build-import-bundle", str(repo), str(path))
        ref = f"app/{APP_ID}/{ARCHES[arch][1]}/master"
        refs = output("ostree", f"--repo={repo}", "refs").decode().splitlines()
        require(refs == [ref], f"Unexpected Flatpak refs: {refs}")
        def cat(name):
            return output("ostree", f"--repo={repo}", "cat", ref, name)
        metadata = configparser.ConfigParser()
        metadata.read_string(cat("/metadata").decode())
        require(metadata.get("Application", "name") == APP_ID, "Flatpak application ID mismatch")
        require(metadata.get("Application", "runtime").split("/")[1] == ARCHES[arch][1], "Flatpak runtime architecture mismatch")
        application_image(cat, "/files/lib/zephyr", version, arch)
        appstream_version(cat(f"/files/share/metainfo/{APP_ID}.metainfo.xml"), version)


def validate_artifact(path, version, arch, kind):
    version_value(version)
    require(arch in ARCHES and kind in FORMATS, "Unsupported release target")
    require(path.is_file() and not path.is_symlink() and path.stat().st_size > 0, f"Missing/empty/linked artifact: {path}")
    require(path.name == f"Zephyr-{version}-linux-{arch}.{kind}", f"Unexpected artifact name: {path.name}")
    {"AppImage": validate_appimage, "snap": validate_snap, "flatpak": validate_flatpak}[kind](path.resolve(), version, arch)


def release_files(directory, version):
    version_value(version)
    expected = {f"Zephyr-{version}-linux-{arch}.{kind}" for arch in ARCHES for kind in FORMATS}
    actual = {p.name for p in directory.iterdir()} if directory.is_dir() else set()
    require(expected <= actual, f"Incomplete release, missing: {sorted(expected - actual)}")
    require(actual <= expected | {"SHA256SUMS", "BUILD-MANIFEST.txt"}, f"Unexpected release files: {sorted(actual - expected)}")
    paths = [directory / name for name in sorted(expected)]
    require(all(p.is_file() and not p.is_symlink() and p.stat().st_size > 0 for p in paths), "Release contains empty, non-file, or linked packages")
    return paths


def checksum(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write_manifest(directory, version):
    files = release_files(directory, version)
    for arch in ARCHES:
        for kind in FORMATS:
            validate_artifact(directory / f"Zephyr-{version}-linux-{arch}.{kind}", version, arch, kind)
    (directory / "BUILD-MANIFEST.txt").write_text("".join(f"{p.name}\n" for p in files))
    (directory / "SHA256SUMS").write_text("".join(f"{checksum(p)}  {p.name}\n" for p in files))


def verify_checksum(path, sums):
    matches = [line.split() for line in sums.read_text().splitlines() if line.split() and line.split()[-1] == path.name]
    require(len(matches) == 1 and len(matches[0]) == 2 and re.fullmatch(r"[0-9a-f]{64}", matches[0][0]), "Missing or duplicate checksum")
    require(matches[0][0] == checksum(path), "Artifact checksum mismatch")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    source = commands.add_parser("source")
    source.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    source.add_argument("--tag")
    artifact = commands.add_parser("artifact")
    artifact.add_argument("path", type=Path)
    artifact.add_argument("--version", required=True)
    artifact.add_argument("--arch", required=True, choices=ARCHES)
    artifact.add_argument("--format", required=True, choices=FORMATS)
    artifact.add_argument("--checksums", type=Path)
    manifest = commands.add_parser("manifest")
    manifest.add_argument("directory", type=Path)
    manifest.add_argument("--version", required=True)
    args = parser.parse_args()
    try:
        if args.command == "source":
            print(source_version(args.root, args.tag))
        elif args.command == "artifact":
            if args.checksums:
                verify_checksum(args.path, args.checksums)
            validate_artifact(args.path, args.version, args.arch, args.format)
            print(f"Validated {args.path}")
        else:
            write_manifest(args.directory, args.version)
            print("Validated all six Linux packages and wrote checksums")
    except (ValueError, OSError, ET.ParseError, configparser.Error, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Release validation failed: {error}\n")


if __name__ == "__main__":
    main()
