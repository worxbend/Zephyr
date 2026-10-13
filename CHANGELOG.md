# Changelog

## 1.2.4 — 2026-10-13

Changes since the last published release, 1.2.1. This also includes the operation
and persistence fixes from the previously unpublished 1.2.2 and 1.2.3 tags.

### Fixed

- Keep SDKMAN operation receipts, recovery decisions, and uncertain outcomes
  consistent when refreshes fail, routes change, or shutdown interrupts work.
- Preserve complete settings snapshots and report unacknowledged saves at shutdown.
- Import snapshots, portable preferences, and project files with bounded reads
  and strict UTF-8 validation. Reject symbolic-link document leaves.
- Export documents atomically after overwrite confirmation. Cancellation,
  destination changes, and failed replacements leave previous contents intact.
- Bound activity-history reads and candidate-cache writes, and redact private
  paths consistently without damaging public URLs.
- Expose page and section heading semantics and retain keyboard-operable controls.
- Isolate test temporary directories, correcting the filesystem test failures
  that blocked the previous native release builds.

### Security

- Update the Kotlin build toolchain from 2.4.0 to 2.4.21 for
  **GHSA-r937-wjx7-w2jp** (unsafe build-cache deserialization).
- Reject symbolic-link website build destinations before deletion and require
  ASCII-only numeric stable release tags.
- Audit resolved JVM runtime, test, build-plugin, and tooling dependencies with
  fail-closed OSV checks. Preserve strict dependency checksum verification.

### Changed

- Enforce pinned, Compose-aware Kotlin formatting and broaden CodeQL coverage to
  Kotlin/Java, Python, and JavaScript/TypeScript.
- Produce JVM line/branch coverage reports and retain CI verification evidence.
- Add report-only synthetic filtering, search, document-read, and inventory
  performance baselines without machine-dependent timing assertions.
- Add a static project website with release-aware downloads and browser
  accessibility checks.

### Compatibility and distribution

- Existing action IDs, version-1 public constants, and persisted document schemas
  remain compatible. Gradle and Compose version pins are unchanged.
- GitHub publication requires verified AppImage, Snap, and Flatpak packages for
  both amd64 and arm64, plus checksums and a build manifest.
- Snap Store publication follows verified GitHub publication. Store approval or
  credential failures do not remove the verified GitHub release.
- Flatpak remains a standalone bundle, not a Flathub listing; activated host
  terminals are not supported by that bundle. Use AppImage or classic Snap for
  host SDKMAN integration.

See [QUALITY_REVIEW.md](QUALITY_REVIEW.md) for local verification evidence and
its remaining scanner/runtime limits.
