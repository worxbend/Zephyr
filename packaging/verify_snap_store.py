#!/usr/bin/env python3
"""Read back the exact uploaded bytes from the public Snap Store channel map."""
import argparse
import hashlib
import json
from pathlib import Path
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from release import ARCHES, require, version_value


def stable_revision(data, version, arch, digest):
    for entry in data.get("channel-map", []):
        channel = entry.get("channel", {})
        if (channel.get("architecture") == arch and channel.get("track") == "latest"
                and channel.get("risk") == "stable" and entry.get("version") == version
                and entry.get("download", {}).get("sha3-384") == digest):
            return entry.get("revision")
    return None


def guard_no_downgrade(data, version):
    target = tuple(map(int, version_value(version).split(".")))
    for entry in data.get("channel-map", []):
        channel = entry.get("channel", {})
        if (channel.get("architecture") in ARCHES and channel.get("track") == "latest"
                and channel.get("risk") == "stable"):
            current = tuple(map(int, version_value(entry.get("version", "")).split(".")))
            require(target >= current, f"Refusing to downgrade stable from {entry['version']} to {version}")


def channel_map(arch, allow_missing=False):
    request = Request("https://api.snapcraft.io/v2/snaps/info/zephyr", headers={
        "Snap-Device-Series": "16", "Snap-Device-Architecture": arch,
    })
    try:
        with urlopen(request, timeout=30) as response:
            return json.load(response)
    except HTTPError as error:
        if allow_missing and error.code == 404:
            return {"channel-map": []}
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True, type=version_value)
    parser.add_argument("--arch", required=True, choices=ARCHES)
    parser.add_argument("--snap-path", required=True, type=Path)
    parser.add_argument("--before-publish", action="store_true", help="Refuse stable-channel downgrades before uploading")
    args = parser.parse_args()
    if args.before_publish:
        try:
            # The shared workflow lock covers both architectures and all tags.
            # Check both Store architectures even if one has not caught up yet.
            for arch in ARCHES:
                guard_no_downgrade(channel_map(arch, allow_missing=True), args.version)
        except (HTTPError, URLError, TimeoutError, ValueError) as error:
            parser.exit(1, f"Cannot safely publish: {error}\n")
        print(f"Stable channel permits {args.version}; no downgrade detected")
        return
    with args.snap_path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha3_384").hexdigest()
    # Bounded retry for public Store propagation, not a long-running monitor.
    for attempt in range(6):
        try:
            revision = stable_revision(channel_map(args.arch), args.version, args.arch, digest)
            if revision is not None:
                print(f"Verified zephyr {args.version} {args.arch}: latest/stable revision {revision}, exact uploaded digest")
                return
        except (HTTPError, URLError, TimeoutError, ValueError) as error:
            print(f"Store readback unavailable: {error}")
        if attempt < 5:
            time.sleep(10)
    parser.exit(1, "Published bytes not visible in latest/stable. Check Snap Store processing/classic review; retry publication only after resolving the Store status.\n")


if __name__ == "__main__":
    main()
