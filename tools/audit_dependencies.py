#!/usr/bin/env python3
"""Query OSV for the resolved Gradle inventory; print JSON, never suppress findings.

Run ./gradlew dependencyInventory first. Network access is explicit and is not
part of offline root check. Exit 0 means no reported advisories, 1 means findings,
and 2 means the inventory or service response could not be fully checked.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
OSV_QUERY_URL = "https://api.osv.dev/v1/querybatch"
MAX_RESPONSE_BYTES = 10 * 1_024 * 1_024


def read_inventory(content: str) -> list[dict]:
    scopes: dict[tuple[str, str], set[str]] = {}
    for line in content.splitlines():
        fields = line.split("\t")
        if len(fields) != 4 or any(not field.strip() for field in fields):
            raise ValueError("Expected four non-empty Gradle inventory fields")
        group, artifact, version, scope = fields
        scopes.setdefault((f"{group}:{artifact}", version), set()).add(scope)
    if not scopes:
        raise ValueError("The Gradle inventory is empty")
    return [{"name": name, "version": version, "scopes": sorted(contexts)}
            for (name, version), contexts in sorted(scopes.items())]


def request_json(payload: dict) -> dict:
    request = Request(OSV_QUERY_URL, data=json.dumps(payload).encode("utf-8"),
                      headers={"Content-Type": "application/json", "User-Agent": "Zephyr-dependency-review"})
    with urlopen(request, timeout=30) as response:
        body = response.read(MAX_RESPONSE_BYTES + 1)
    if len(body) > MAX_RESPONSE_BYTES:
        raise ValueError("OSV response exceeds the safety limit")
    result = json.loads(body)
    if not isinstance(result, dict):
        raise ValueError("OSV response must be an object")
    return result


def audit(packages: list[dict], query=request_json) -> dict:
    findings = []
    for start in range(0, len(packages), 100):
        batch = packages[start:start + 100]
        response = query({"queries": [{"package": {"ecosystem": "Maven", "name": item["name"]},
                                       "version": item["version"]} for item in batch]})
        results = response.get("results")
        if not isinstance(results, list) or len(results) != len(batch):
            raise ValueError("OSV returned an incomplete batch")
        for package, result in zip(batch, results):
            if not isinstance(result, dict) or not isinstance(result.get("vulns", []), list):
                raise ValueError("OSV returned an invalid package result")
            if set(result) - {"vulns", "next_page_token"} or result.get("next_page_token"):
                raise ValueError("OSV returned an unsupported or incomplete package result")
            ids = []
            for vulnerability in result.get("vulns", []):
                if not isinstance(vulnerability, dict) or not isinstance(vulnerability.get("id"), str) or not vulnerability["id"]:
                    raise ValueError("OSV returned an invalid advisory")
                ids.append(vulnerability["id"])
            if ids:
                findings.append({**package, "advisories": sorted(set(ids))})
    return {"database": "OSV", "ecosystem": "Maven", "packages_checked": len(packages), "findings": findings}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", type=Path, default=ROOT / "build/reports/dependencies/inventory.tsv")
    args = parser.parse_args()
    try:
        report = audit(read_inventory(args.inventory.read_text(encoding="utf-8")))
    except (ValueError, OSError, TimeoutError) as failure:
        print(f"Dependency audit incomplete: {failure}", file=sys.stderr)
        return 2
    print(json.dumps(report, indent=2))
    return 1 if report["findings"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
