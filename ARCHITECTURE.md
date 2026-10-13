# Architecture and verification contracts

Zephyr is a desktop application wrapping SDKMAN, not a replacement package
manager or a published general-purpose framework. Keep the existing `shared`
Kotlin Multiplatform module, its JVM target, and `desktopApp` Gradle module.
Package boundaries precede any future module split. There is no file/function
line-count gate and no architecture suppression baseline.

## Package dependency direction

Paths below are relative to `shared/src`, under `com/worxbend/zephyr`.

| Package / location | Responsibility and permitted direction |
| --- | --- |
| `commonMain/domain` | Identifiers, evidence, plans, eligibility, operation receipts and recovery models. Depends on domain, Kotlin common stdlib and common coroutines only. No presentation or service dependencies. |
| `commonMain/application` | Application use cases and operation ownership. May depend on domain, application, common `data` ports, settings and logging contracts, plus stdlib/coroutines. No viewmodel, feature, Compose or concrete adapter dependency. This boundary is enforced even before every use case is extracted. |
| `commonMain/data` | Narrow contracts/results for SDKMAN, document workflows, notifications, launchers, exporters and storage. Existing `expect fun create…` declarations describe the JVM selection seam; callers must construct adapters in desktop, not common code. |
| `commonMain/settings` | Settings models, transformations and the application-owned revisioned writer. Persistence representation and JVM preferences access belong in `jvmMain/settings`. |
| `commonMain/features/*` | Actual catalog, maintenance, tasks, settings and toolchain screens. `*Presenter.kt` owns feature IO/draft state through injected common ports and coroutines, without Compose, JVM, process or factory dependencies. Screens render presenter state and invoke callbacks. |
| `commonMain/runtime` | Nonvisual application-lifetime policy. `AppPolicyCoordinator` observes relevant evidence/settings projections; it owns notification tracking, evidence-aware cleanup observations and the explicit opt-in metadata schedule. It currently depends on `ZephyrUiState`, so it is not yet a core application package. |
| `commonMain/viewmodel` | Existing UI-facing facade and state/navigation bridge. `application/operation` owns admitted execution, receipts, recovery and interruption. Catalog/audit orchestration remains in this facade with generation-fenced publication; do not move it wholesale into application. |
| common root UI files | `App(viewModel, settingsStore)`, shell, UI primitives and pure presentation helpers retained during the feature split. `AppServices` holds injected common ports. `LocalAppServices` has no production default: missing provision is an error. |
| `jvmMain/data`, `sdkman`, `settings`, `storage` | Concrete process/filesystem/preferences/secret/dialog adapters and internal codecs. Depend inward on common contracts and domain. JVM implementations are not common presentation dependencies. |
| `desktopApp/src/main/kotlin` | Composition and window lifecycle: production adapter construction, service provision, application startup and acknowledged shutdown. This is the only application assembly location. |

`workbench` and `ui/components` are possible subsequent package moves, not
currently claimed extracted modules. Catalog/maintenance/task screens are not
all independent presenters yet. Settings and project/snapshot workflows do
have injected, coroutine-based feature presenters. Do not infer complete
migration from a renamed directory.

## Enforced static rules

`tools/architecture_check.py` scans **all production commonMain Kotlin files**,
including newly added files; it fails if the source tree is missing or empty.
Tests and JVM/desktop sources are intentionally outside that scope because
adapters and composition must be able to use platform APIs.

- `COMMON_PLATFORM`: prohibit Java/JVM, Swing, Android, platform Kotlin helpers,
  Commons Exec and coroutine Swing imports/references. Fully qualified Java
  references and implicit JVM `System` APIs are checked as well as imports.
- `COMMON_ADAPTER`: prohibit concrete `Jvm…`, JVM-only `sdkman`/`storage`
  packages and named process/filesystem/storage/secret implementation references,
  including wildcard and aliased imports.
- `COMMON_FACTORY`: prohibit production `create…` factory imports and references
  anywhere in common code, including viewmodel defaults, callable references,
  aliased calls and string interpolation expressions. Only an `expect fun`
  declaration in a common `data`/`settings` contract is exempt. No file is
  broadly exempt. New expect factory names are discovered from source; ordinary
  pure `create…` helpers are not banned by naming alone.
- `CORE_DEPENDENCY`: domain/application reject Compose and Okio and enforce the
  inward import directions in the table. Presenters reject Compose and Okio.
  Qualified references are checked too. Common coroutines are permitted.
- `PRESENTER_DEPENDENCY`: feature presenters cannot reference JVM adapter
  `sdkman`/`storage` packages; depend on common service ports instead.
- `COMMON_LIFECYCLE`: common code cannot directly use process/thread APIs or
  `GlobalScope`; lifecycle ownership must be explicit.

Both the declared package and directory determine domain/application scope, so
moving the file or renaming its package alone does not bypass these rules.
The checker lexes nested block/line comments, character literals, escaped/raw
strings, backtick identifiers and executable `${…}`/`$name` interpolation;
literal examples in comments/text do not become dependencies. Local candidate
variables named `java`/`jdk` are not mistaken for Java namespaces.

This is a dependency guard, **not a Kotlin compiler or semantic resolver**.
It does not prove a coroutine is correctly cancelled, infer hidden dependencies
through arbitrary wrappers/typealiases, enforce every screen's statelessness,
or implement all future Kotlin lexical extensions (for example multi-dollar
string interpolation). Compilation and behavioural tests remain required.
Extend rules with a failing synthetic fixture before accepting a new seam;
do not hide genuine violations behind an allowlist.

## Ownership and lifecycle contracts

### Desktop and presentation

1. Desktop constructs one application runtime's production ports, settings
   writer, UI facade and policy coordinator before rendering. Provide
   `AppServices` via `LocalAppServices`; previews and actual route tests provide
   memory ports, never ambient SDKMAN/preferences/Secret Service adapters.
2. `App` observes injected state, renders and emits UI callbacks. Theme/accessibility
   queries are UI lifecycle effects. It must not instantiate persistence,
   perform durable audit policy, replay history as live notification events, or
   own command execution because it happens to be composed.
3. Existing settings/project/snapshot screens use `remember(services)`, a
   composition parent coroutine scope and `DisposableEffect` to close their
   presenter. Presenters own child jobs and rethrow `CancellationException`.
   Drafts/messages belong to that feature instance and reset when it leaves
   composition. Persisted settings/workspaces belong to application state.
4. Route-owned latest reads must validate generation **and candidate identity**
   before publication. Navigation may cancel obsolete reads but must not cancel
   accepted application-owned mutations. Keep identity checks in presentation
   selectors as defense in depth; generation checks alone are not a test.
5. Shutdown must stop new work and policy collectors, acknowledge accepted
   operations/interruption according to coordinator ownership, drain settings
   with bounded `flushAndClose`, and only then exit. `SettingsCloseResult.Saved`
   acknowledges accepted revisions; `Failed`/`TimedOut` are not success and must
   be surfaced with an explicit retry/exit choice. A timed-out writer can still
   finish its current IO. Do not perform a blocking drain on the UI dispatcher.

### Operations and evidence

- An accepted reviewed request has one stable ownership identifier and exactly
  one in-process owner spanning admission, durable plan, dispatch and receipt.
  Busy/rejected admission must be explicit and must not create phantom running
  entries. Best-effort inventory/detail reads cannot evict admitted mutations.
- Persist execution receipts before independent refresh work. A refresh warning
  can make displayed inventory stale, never turn a verified successful command
  into a failed execution receipt. Invalidate affected evidence consistently.
- Recovery cannot reconcile or rewrite a live-owned entry. Serialize journal
  changes with execution/recovery ownership or revision-checked persistence.
- Preserve per-step partial application and `Indeterminate` outcomes. Unknown
  inventory/audit/measurement is not absence, zero bytes or verified failure.
  Cleanup presentation uses the common eligibility policy; adapters revalidate
  default/protected targets and trusted evidence before mutation.
- Planned commands validate required/forbidden targets; `CommandOutcome`
  validates success/status consistency. These are current constructor
  invariants, not a claim that a sealed-command migration has occurred.
- SDKMAN and crashes do not support global exactly-once external execution.
  Recovery uses verified postconditions and honest replay/idempotency decisions.

### Persistence and platform adapters

- The operation ledger codec is independent of file replacement. Complete plan
  count/order/identity and document integrity are validated; unsupported,
  corrupt and unreadable originals are not empty history or permission to save
  a shortened recovery plan. Current error variants are typed exceptions at
  the adapter boundary, not a claimed public `Missing/Valid/Corrupt` API.
- `storage/AtomicDocumentStore` performs bounded, no-follow leaf reads and
  sibling-temp replacement with file force and atomic move. It fails rather
  than falling back to non-atomic replacement; directory force is best effort.
  The immediate destination directory is checked, but this is not a general
  adversarial ancestor-race/filesystem sandbox guarantee. Ownership/permissions
  are attempted according to platform support.
- Settings use a versioned validated snapshot codec plus two complete chunked
  preferences generations. Stage/flush the inactive generation before changing
  its commit pointer. This is **not an atomic filesystem settings document**.
  It addresses preference value limits and mixed-key snapshots. Failed saves
  retain a dirty revision and support explicit unchanged-value retry.
- One process/application writer is supported. Neither preferences generations
  nor the atomic ledger store promise cross-process compare-and-swap. Adding
  multi-instance support requires an explicit locking/revision contract.
- Process sessions own launch, cancellation, descendants, output bounds,
  deadlines and bounded cleanup. Secret-tool deadlines and missing/unavailable/
  failed outcomes remain distinct. Bind credentials to proxy identity and do
  not place secrets in argv, logs, persisted journals or support exports.
- Document workflows retain their common chooser/read-write facades. JVM services
  inject `DocumentDialogs` and use `UserDocumentFiles` for 1 MiB opened-channel
  reads, strict UTF-8, leaf-symlink rejection, overwrite confirmation, cancellation
  checks and atomic same-directory replacement. User directories are not created
  or chmodded; parent aliases are allowed except for existing project-workspace
  path validation. Destination-change checks are not hostile-race protection.
  The proposed standalone common `FileSelection` port is not fully extracted.

## Verification entry points

```sh
python3 -B tools/architecture_check.py
python3 -B -m unittest discover -s tools -p 'test_architecture_check.py' -v
./gradlew architectureCheck architectureCheckerTest siteBuilderTest --console=plain
./gradlew check --console=plain
```

The root `base` lifecycle's `check` explicitly depends on `architectureCheck`,
`architectureCheckerTest`, `siteBuilderTest`, `dependencyAuditTest`,
`kotlinFormatCheck`, `:shared:check` and `:desktopApp:check`. Shared `check` also
produces `jvmCoverageReport`. Existing CI invokes root `./gradlew check`; Python 3
is available in its packaging steps. The architecture, dependency-audit helper,
and site-builder tests use stdlib only and never execute
SDKMAN/JVM services. Their Gradle scratch roots are
`build/architecture-tests/scratch` and `build/site-builder-tests/scratch`.

`shared` JVM tests redirect Java preferences to `shared/build/test-preferences`
and both `java.io.tmpdir` and `TMPDIR` to `shared/build/test-fixtures`. A caller
need not supply an ambient `TMPDIR`. Explicit shell/process
fixtures must also use isolated temporary roots and fake SDKMAN homes; those
properties do not sandbox arbitrary scripts or Secret Service calls. Retain
warnings-as-errors, strict dependency verification, CodeQL and separate native
packaging gates. Application race tests use injected dispatchers and
`kotlinx-coroutines-test`/suspension gates, not elapsed-time guesses.

## Formatting, coverage and dependency analysis

The earlier read-only ktlint probe found 4,184 diagnostics across 170 files.
That formatting follow-up is now implemented: pinned ktlint 1.8.0 runs through
`kotlinFormat` and root-gated `kotlinFormatCheck` for Kotlin sources and root/module
build scripts. `.editorconfig` uses the official style and annotation-aware
`@Composable` naming. There is no blanket baseline or disabled standard ruleset.
Six original public action-ID constant names have declaration-only naming
exceptions to preserve version-1 source compatibility; additive uppercase names
are used internally. Tests compile and compare the legacy aliases.

```sh
./gradlew kotlinFormat --console=plain
./gradlew kotlinFormatCheck --console=plain
./gradlew :shared:jvmCoverageReport --console=plain
./gradlew dependencyInventory --no-configuration-cache --console=plain
python3 -B tools/audit_dependencies.py
./gradlew :shared:performanceProbe --console=plain
```

Run formatting separately from compilation/tests and review formatting churn
separately from behavioral changes. Ktlint is resolved from Maven Central with
strict checksum verification; its complete CLI JAR SHA-256 is
`369ad2b789f95a011f807e1fcb690ccef80bd7cd014fd139e73ae82dcc0baeab`.
Compiler warnings, architecture checks and CodeQL remain separate correctness
gates; no arbitrary Detekt complexity policy was introduced.

JaCoCo 0.8.15 produces shared JVM XML/HTML/CSV coverage under
`shared/build/reports/jacoco/jvmCoverageReport/`, without source exclusions or an
invented percentage threshold. CI retains test and coverage evidence.
`dependencyInventory` exports runtime/test/tooling/build-plugin scopes; its task
uses execution-time resolution, so run that explicit audit without configuration
cache. OSV network lookup is separate from offline `check` and fails closed on
incomplete or unsupported results. Kotlin 2.4.21 fixes the confirmed build-plugin
advisory in 2.4.0; Gradle/Compose pins remain unchanged. Existing verification
checksums were retained when adding new artifacts.

The optional performance probe measures synthetic filtering, search, bounded
reads and a task-owned filesystem inventory. It has warmups and samples, but no
machine-dependent timing assertion, SDKMAN command or network access. It does
not profile actual UI recomposition. Current metrics, advisory disposition and
remaining scanner/runtime limitations are in [QUALITY_REVIEW.md](QUALITY_REVIEW.md).

References: [ktlint CLI](https://ktlint.github.io/ktlint/1.8.0/install/cli/),
[Maven coordinates](https://central.sonatype.com/artifact/com.pinterest.ktlint/ktlint-cli/1.8.0).

## Acceptance criteria and integration status

The final serialized integration check executed root architecture tasks and
shared/desktop checks with
`env -u TMPDIR ./gradlew check :desktopApp:createDistributable --rerun-tasks --console=plain`.
JUnit XML reports **373 Kotlin/Compose tests in 73 suites**, zero failures,
errors or skips. The checker has **31 passing synthetic tests** and scans
**97 production commonMain files with zero violations**. Packaging contract
checks separately passed **42 tests** (13 release, 14 Flatpak, 15 Snap).

Desktop `Main.kt` constructs every production port and one `AppRuntime` before
composition. It provides `LocalAppServices` and invokes injected `App`.
Shutdown replaces route content (disposing feature presenters), joins policy
collectors, stops operation admission and acknowledges interruption/receipt
persistence, then drains settings. Only acknowledged success exits normally.
Failed/timeout results retain an explicit retry and an unconfirmed-exit choice.
`AppRuntimeTest`, `AppShutdownCoordinatorTest`, `AppShutdownScreenTest` and
`AppPolicyCoordinatorTest` cover memory-only saved/failure/timeout behavior,
policy shutdown and blocked settings hydration. `desktopApp:test` remains
NO-SOURCE: desktop compilation is verified, not a native whole-window E2E run.

Independent review found and fixed three follow-up gaps before the final run:
old audits could resurrect invalidated findings, indeterminate-only history
lacked a verification action, and colon/apostrophe path text escaped redaction.
Gated application, actual route and ledger/CSV/support tests cover these cases.
An uncertain task can be verified as satisfied without replaying a mutation.

Verified acceptance:

- [x] No architecture suppression baseline or per-file production exemption.
- [x] Fresh root Gradle check executes both architecture tasks and shared/desktop checks.
- [x] Desktop compiles with injected `App` and provides every `AppServices` port.
- [x] Memory runtime startup/shutdown exercises saved/failure/timeout and policy joins.
- [x] Gated operation tests cover admission/read exclusion, receipt truth, live recovery,
      cancellation, per-step uncertainty, detail identity and stale audit generations.
- [x] Actual settings/project/workspace/snapshot/task/detail/local-only routes compose
      with memory ports; visual component captures remain labelled as component tests.
- [x] JVM fixtures cover bounded processes, containment, corruption, replacement,
      settings commits/drains, destination-bound secrets, redaction and unknown totals.
- [x] Release/Flatpak/Snap packaging unit/contracts pass; these are not native artifacts.
- [x] All F01-F18 fixes and concrete regression evidence are mapped in
      `IMPLEMENTATION_ACCEPTANCE.md`.

Verification limits: no real SDKMAN mutation, Secret Service lookup, installed
native package smoke run, macOS runtime or store publication was performed.
The process and persistence limits stated above remain deliberate boundaries.
The production application-image launcher additionally opened a native window
under an isolated home and absent-SDKMAN fixture. That startup smoke does not
validate installed packages, screen-reader integration or interactive shutdown.
The formatter and measured coverage are now verified gates/reporting tasks;
no mutation score, Sonar result or local CodeQL analysis is claimed.

