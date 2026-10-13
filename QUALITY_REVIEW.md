# Code quality review and improvement plan

Scope: the checkout based on `ea513da`, plus this local improvement pass.
This is a risk-focused source review with local verification, not a complete
line-by-line audit or a SonarCloud analysis. The contracts in
[ARCHITECTURE.md](ARCHITECTURE.md) and the Gradle/Compose pins are retained.
Kotlin was updated from 2.4.0 to 2.4.21 to fix a confirmed dependency advisory.
This records pre-release verification; scanner and runtime limits below describe
that pass, not the status of any subsequent release workflow.

## Relevant skills

Use these skills for the concerns actually present in this repository:

- **`worxbend-repository-context`**: preserve the desktop SDKMAN wrapper,
  common/JVM boundaries, packaging contracts, and existing verification commands.
- **`code-review-checklist`**: check correctness, edge cases, security,
  maintainability, resource ownership, and test evidence together.
- **`kotlin-coroutines-expert`**: review cancellation, application versus route
  ownership, Flow publication, dispatcher selection, and deterministic race tests.
- **`security-and-hardening`**: review command arguments, untrusted local files,
  destructive paths, proxy credentials, export privacy, and dependency advisories.
- **`sonar-issue-prevention`**: check Python output paths, ASCII versus Unicode
  regex semantics, temporary storage, workflow shell input, and control-flow
  complexity. Its Scala examples are not a reason to change Kotlin architecture.
- **`test-driven-development`**: reproduce defects before fixing them; retain
  existing hermetic fixtures and validate observable results rather than mocks.
- **`andrej-karpathy`**: keep edits small, justified, and verifiable; avoid
  speculative abstractions and cleanup that only changes a score.
- **`documentation-and-adrs`** and **`human-copywrite`**: record decisions,
  operational limits, verification commands, and honest user-facing outcomes.

Additional skills fit later, bounded work:

- **`safe-refactor`** for presenter/use-case extraction with behavior held stable.
- **`github-actions-advanced`** for CI gates and scanner integration.
- **`workstation-installers`** for installer artifact integrity and path ownership.
- **`performance-profiling`** for measured desktop/UI or filesystem bottlenecks.
- **`browser-testing-with-devtools`** for website runtime investigation when that
  connector is available. Existing Playwright tests provide local browser checks.
- **`sonarcloud-triage`** when a server, project, branch, and analyzed revision
  are available. No Sonar project/configuration was found in this checkout.

Backend, database, React, mobile, Mill, and ZIO skills are not defaults for this
Kotlin/Compose Desktop application. Do not introduce those stacks to improve it.

## Fixes in this pass

### Activity history privacy and storage

`JvmActivityStore` used separate, weaker path-redaction regexes. They left
private filename suffixes in some messages and damaged public URLs. It now uses
`SensitiveTextRedactor`, the same policy as task/support exports, before bounding
message length. Tests read the decoded persisted messages, not merely Base64
bytes, and cover quoted paths, spaces, apostrophes, UNC paths, and public URLs.

The store also followed leaf symlinks and read the complete history without a
size limit. It now uses the existing `AtomicDocumentStore`: bounded 8 MiB reads,
no-follow leaf access, forced temporary writes, owner-only permissions where
supported, and atomic replacement without a non-atomic fallback. The ledger
schema and default 100-event retention remain unchanged. Unsupported atomic
replacement now fails instead of silently weakening durability.

This retains the storage utility's documented limits: it is not an adversarial
ancestor-race sandbox or a cross-process locking mechanism.

### Candidate cache resource bounds

`JvmCandidateMetadataCacheStore` read arbitrary-sized files into memory. It now
uses the bounded document reader and rejects encoded writes larger than 8 MiB.
Oversized reads remain cache misses; rejected writes retain previous bytes and
remove temporary files. Existing ancestor-symlink checks and atomic-replacement
failure tests remain in place.

### Website build destination and release validation

`tools/build_site.py` resolved `build/` before checking containment. A symlinked
build root could therefore authorize deleting an external directory. The builder
now normalizes the output without following aliases and rejects symlinks in the
build root, intermediate directories, and output leaf before deletion. Tests
preserve external sentinel bytes and unrelated in-tree artifacts. This is a
pre-operation check, not protection against hostile concurrent filesystem races.

Stable version validation now uses ASCII digits throughout. Previously, Python's
Unicode-aware `\d` accepted tags containing non-ASCII digit suffixes.

### Repeatable verification

Root `check` now includes `siteBuilderTest`, using a task-owned Python scratch
root. The shared JVM test task supplies `TMPDIR` as well as `java.io.tmpdir`;
existing filesystem tests no longer require the caller to configure an ambient
scratch directory. Their failures without `TMPDIR` were reproduced first.

## Verification evidence

- Fresh `env -u TMPDIR ./gradlew check :desktopApp:createDistributable
  --rerun-tasks --console=plain`: passed, including formatter, architecture,
  dependency-audit helper regressions, JVM coverage, and desktop compilation.
- Kotlin JUnit reports: 373 tests in 73 suites; no failures, errors, or skips.
- Architecture scan: 97 common production files, zero violations; 31 checker tests.
- Python tools: 48 passing tests, including 12 site-builder and 5 dependency-audit tests.
- Packaging contracts: 42 passing tests across release, Flatpak, and Snap tooling.
- Chromium Playwright: 13 passing tests, including responsive layouts, keyboard
  focus, both architecture states, JavaScript fallback, and automated accessibility.
- Workflow lint: the CI-pinned actionlint 1.7.12 command passed.
- Shell and Python syntax checks passed; `git diff --check` passed.
- `npm --prefix site audit --audit-level=moderate`: zero reported advisories.
- Resolved Gradle OSV audit: 145 unique Maven package/version pairs, zero reported
  advisories after the Kotlin patch; runtime, tests, tooling, and build plugins included.
- Dependency verification: 90 new SHA-256 entries; all 370 previous checksums retained.
- Native application-image smoke: the production launcher created a visible
  Zephyr window (2560 × 1604 physical client pixels) and remained alive, using isolated home/preferences,
  software rendering, and an absent SDKMAN fixture. No fatal error was logged.

The default browser-test port was already occupied. Verification used a
task-owned loopback server on an OS-assigned port through the existing `SITE_URL`
option, without stopping or trusting the unrelated server.

No real SDKMAN mutation, Secret Service lookup, native package installation,
release publication, cloud scanner run, or remote issue disposition was performed.
The application-image startup smoke is not installed-package, graceful-shutdown,
SDKMAN-operation, or assistive-technology E2E verification. No commit, push, or
remote scanner execution occurred during this local verification pass.

## Follow-up implementation and disposition

### 1. Document import/export boundaries — fixed

`storage/UserDocumentFiles.kt` now supplies bounded opened-channel reads, strict
UTF-8 decoding, leaf-symlink rejection, and same-directory atomic exports for
snapshot, portable-preferences, and project-toolchain services. The default and
existing project limit are 1 MiB. Overwrite confirmation precedes staging;
cancellation or a detected destination change prevents replacement. Unsupported
atomic moves fail without truncating previous bytes; temporary files are removed.
User-selected parent directories are not created, chmodded, or confined to build
roots. Parent-directory aliases are allowed for these document operations;
project workspace imports retain their stricter existing path validation.

The JVM `DocumentDialogs` seam makes chooser cancellation and overwrite denial
testable without opening Swing dialogs. `UserDocumentFilesTest` and
`JvmDocumentWorkflowsTest` cover oversized/stream-growing inputs, malformed UTF-8,
round trips, symlinks, cancellation, changed destinations, and failed replacement.
This is not a hostile concurrent-filesystem sandbox or cross-process lock.

### 2. Scanner evidence — reviewed; remote rerun still required

GitHub's paginated API reports zero open code-scanning alerts. The latest CodeQL
analysis reports zero results for `refs/heads/main` at
`ea513dab86b92ede524f155523c5f48b2ce67edd` (2026-10-12). It does not cover local edits.
The workflow now includes Java/Kotlin, Python, and JavaScript/TypeScript, plus a
separate resolved-dependency audit. No alert was dismissed or suppressed.

No Sonar server/project/branch was provided or configured. A Sonar analysis or
quality-gate result therefore remains unavailable; none is claimed.

### 3. Formatting and maintainability — enforced

Pinned ktlint 1.8.0 now checks all Kotlin sources and root/module build scripts
through root `check`. `.editorconfig` recognizes `@Composable` function naming
without disabling the standard ruleset. The baseline was formatted; remaining
wildcard imports, wrapping, filename conventions, and redundant `Unit` branches
were corrected. No Detekt blanket baseline or speculative responsibility
extraction was introduced.

Action-ID string values and contract version remain unchanged. Uppercase constant
names are additive: six original public names remain `const` aliases with explicit,
declaration-only naming exceptions for version-1 source compatibility. A regression
compiles and checks every legacy alias. There is no global naming-rule exclusion.
Formatting is substantial checkout churn and should be reviewed separately from
behavioral changes when preparing commits. Publication uses separate formatting,
hardening/tooling, and release-metadata commits.

### 4. Dependency review — advisory fixed and repeatable audit added

The resolved graph initially exposed `GHSA-r937-wjx7-w2jp` in Kotlin Gradle plugin
2.4.0 (unsafe build-cache deserialization). Updating Kotlin and its matching compiler
plugin to patched 2.4.21 removed the finding; Gradle and Compose remain pinned.
Strict checksum verification and warnings-as-errors remain enabled.

`dependencyInventory` records resolved coordinates and their configuration scopes.
`tools/audit_dependencies.py` checks every OSV batch, retains reachability context,
and fails on malformed/incomplete responses, error fields, or unconsumed pagination.
It is an explicit network check, separate from offline root `check`. CI retains its
inventory/audit reports and also audits the website lockfile. A clean advisory
lookup is not a proof that native libraries, the JDK, or operating-system packages
have no vulnerabilities.

```sh
./gradlew dependencyInventory --no-configuration-cache --console=plain
python3 -B tools/audit_dependencies.py > build/reports/dependencies/osv-audit.json
npm --prefix site audit --ignore-scripts
```

### 5. Coverage and regression sensitivity — measured

JaCoCo 0.8.15 emits XML, HTML, and CSV through `:shared:jvmCoverageReport`, included
in shared/root `check`; CI retains coverage and test reports. No source exclusions,
new percentage threshold, deleted assertion, or skipped test was used.

Measured shared JVM coverage is **66.5% lines** (8,630/12,982) and **48.1% branches**
(3,393/7,049), including Compose/compiler-generated code. Focused evidence:

- Storage: 99.0% lines, 78.6% branches.
- Settings: 97.3% lines, 67.2% branches.
- Operation coordination/execution/recovery package: 84.8% lines, 66.1% branches.
- SDKMAN adapters/parsers: 87.7% lines, 63.6% branches.
- `OperationRecovery.kt`: 36/40 lines and 24/29 branches.
- `SdkmanListParser.kt`: 208/212 lines and 165/222 branches.

Failing reproductions establish sensitivity to the fixed persistence, path,
size-boundary, and heading defects. This is regression evidence, not an automated
mutation score. Broad mutation testing and an agreed coverage threshold remain
future quality-policy decisions, not findings silently marked as passing.

### 6. Performance and desktop accessibility — baselines and concrete fix

The report-only `:shared:performanceProbe` uses isolated synthetic fixtures,
10 warmups and 20 measured samples. One Linux amd64/JDK 21.0.11 run measured median
latencies of 1.63 ms for filtering/sorting 10,000 versions, 4.75 ms for searching
10,078 entries, 7.34 ms for scanning/rechecking 1,000 files, and 3.28 ms for a bounded
512 KiB document read. These are local baselines, not timing gates, cross-machine
comparisons, recomposition profiles, or proof of an optimization. No optimization
was made from a guess.

A failing accessibility regression exposed missing page/section heading semantics;
those headings now advertise their role. New component tests verify heading versus
subtitle semantics, keyboard-operated radio selection, and a polite error live
region with keyboard dismissal. Existing disabled-control, icon-label, focus-order,
large-text, contrast, and reduced-motion tests remain intact.

The production jpackage application image also opened its native window under an
isolated home and missing-SDKMAN fixture. This extends startup evidence without
changing the developer's SDKMAN or preferences. Actual screen-reader behavior,
installed Deb/Rpm/AppImage smoke, realistic UI recomposition profiling, and Linux
architecture release gates are not replaced by this test.

## Evidence locations and remaining external validation

- `shared/build/test-results/jvmTest/` and `shared/build/reports/tests/jvmTest/`.
- `shared/build/reports/jacoco/jvmCoverageReport/` (XML/HTML/CSV).
- `build/reports/dependencies/inventory.tsv` and `osv-audit.json`.
- `build/reports/quality/performance-baseline.tsv`, `desktop-smoke.json`,
  `desktop-smoke.log`, and CodeQL API evidence.
- Browser reports remain under `build/`; the loopback test server was stopped.

Generated evidence is from the pre-release local pass, not checked-in data.
All concrete source defects
identified in this review have regression-tested fixes. Still unverified are a
Sonar project quality gate, CodeQL on these unpushed edits, automated mutation
scores, real-data recomposition profiling, installed-package/assistive-technology
E2E, and cross-architecture release artifacts. They need their actual scanner,
runtime, or release environment; local passing checks do not establish them.
