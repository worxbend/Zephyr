#!/usr/bin/env python3
"""Build Zephyr's dependency-free Pages site from published release metadata."""
from __future__ import annotations

import argparse
import html
import json
import os
from pathlib import Path
import re
import shutil

REPOSITORY = "https://github.com/worxbend/Zephyr"
SITE_URL = "https://worxbend.github.io/Zephyr/"
FORMATS = ("AppImage", "flatpak", "snap")
ARCHITECTURES = ("amd64", "arm64")
ROOT = Path(__file__).resolve().parents[1]


def release_values(release: dict) -> dict[str, str]:
    """Fail closed on unpublished releases, missing assets or foreign links."""
    if release.get("draft") is not False or release.get("prerelease") is not False:
        raise ValueError("Downloads require a published, stable release")
    tag = release.get("tag_name", "")
    if not isinstance(tag, str) or not re.fullmatch(r"v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)", tag):
        raise ValueError("Expected a stable numeric release tag")
    version = tag[1:]
    release_url = f"{REPOSITORY}/releases/tag/{tag}"
    if release.get("html_url") != release_url:
        raise ValueError("Release URL must belong to the canonical repository and tag")
    assets = release.get("assets")
    if not isinstance(assets, list):
        raise ValueError("Expected an asset list")
    urls = {}
    for asset in assets:
        if not isinstance(asset, dict):
            raise ValueError("Invalid release asset")
        name = asset.get("name")
        if not isinstance(name, str) or name in urls:
            raise ValueError("Duplicate or invalid asset name")
        size = asset.get("size")
        if type(size) is not int or size <= 0:
            raise ValueError("Release assets must have a positive size")
        expected = f"{REPOSITORY}/releases/download/{tag}/{name}"
        if asset.get("browser_download_url") != expected:
            raise ValueError("Asset URL must match its release and filename")
        urls[name] = expected
    required = {"SHA256SUMS", "BUILD-MANIFEST.txt"}
    required.update(f"Zephyr-{version}-linux-{arch}.{fmt}" for arch in ARCHITECTURES for fmt in FORMATS)
    if not required.issubset(urls):
        raise ValueError(f"Release is missing assets: {sorted(required - urls.keys())}")
    values = {"VERSION": version, "RELEASE_URL": release_url, "CHECKSUMS_URL": urls["SHA256SUMS"]}
    for arch in ARCHITECTURES:
        for fmt in FORMATS:
            values[f"{fmt.upper()}_{arch.upper()}"] = urls[f"Zephyr-{version}-linux-{arch}.{fmt}"]
    return values


def render(template: str, values: dict[str, str]) -> str:
    keys = set(re.findall(r"\{\{([A-Z0-9_]+)\}\}", template))
    if unknown := keys - values.keys():
        raise ValueError(f"Unknown template placeholders: {sorted(unknown)}")
    for key in keys:
        template = template.replace("{{" + key + "}}", html.escape(values[key], quote=True))
    if "{{" in template or "}}" in template:
        raise ValueError("Unresolved template placeholder")
    return template


def build(root: Path, output: Path, release: dict, source_sha: str = "local") -> None:
    # Validate everything before changing the output directory.
    values = release_values(release)
    source = root / "site"
    rendered = render((source / "index.html").read_text(encoding="utf-8"), values)
    copies = {
        "assets/styles.css": source / "assets/styles.css",
        "assets/app.js": source / "assets/app.js",
        "assets/icon.png": root / "packaging/linux/com.worxbend.zephyr.png",
        **{f"assets/screenshot-{i}.png": root / f"Screenshot-{i}.png" for i in range(1, 4)},
    }
    for path in copies.values():
        if not path.is_file() or path.is_symlink():
            raise ValueError(f"Missing or linked site asset: {path}")
    # Normalize without following aliases: resolving build/ first would authorize
    # deleting external data when that directory is a symlink.
    output = Path(os.path.abspath(output))
    build_root = root.resolve() / "build"
    if output == build_root or not output.is_relative_to(build_root):
        raise ValueError("Site output must be a subdirectory of the repository build directory")
    current = root.resolve()
    for component in ("build", *output.relative_to(build_root).parts):
        current = current / component
        if current.is_symlink():
            raise ValueError("Site output paths cannot contain symbolic links")
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    (output / "assets").mkdir()
    (output / "index.html").write_text(rendered, encoding="utf-8")
    for destination, path in copies.items():
        shutil.copyfile(path, output / destination)
    (output / ".nojekyll").touch()
    (output / "robots.txt").write_text(f"User-agent: *\nAllow: /\nSitemap: {SITE_URL}sitemap.xml\n", encoding="utf-8")
    (output / "sitemap.xml").write_text(
        f'<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"><url><loc>{SITE_URL}</loc></url></urlset>\n',
        encoding="utf-8",
    )
    metadata = {"source_sha": source_sha, "release_tag": release["tag_name"], "release_url": values["RELEASE_URL"]}
    (output / "build-meta.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print(f"Built {output}: published {release['tag_name']}, source {source_sha}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-json", type=Path, default=ROOT / "site/release.json")
    parser.add_argument("--output", type=Path, default=ROOT / "build/site")
    parser.add_argument("--source-sha", default="local")
    args = parser.parse_args()
    build(ROOT, args.output, json.loads(args.release_json.read_text(encoding="utf-8")), args.source_sha)


if __name__ == "__main__":
    main()
