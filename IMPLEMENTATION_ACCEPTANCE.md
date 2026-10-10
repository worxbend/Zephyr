# Implementation acceptance

The F01–F18 findings from the review of `84b043d` are fixed and covered by
regressions. This is an implementation/verification record, not a claim that
SDKMAN operations are globally transactional or that native releases were tested.

## Final verification

- `./gradlew check --rerun-tasks --console=plain`: successful fresh execution,
  including shared tests, desktop compilation, `architectureCheck` and
  `architectureCheckerTest`.
- JUnit XML: **350 tests, 70 suites, zero failures/errors/skips**. The original
  baseline was 196 tests. Counts are parsed from actual XML, not source annotations.
- Architecture checker: **31 tests pass**, **97 commonMain files, zero violations**.
- Packaging unit/contracts: **42 tests pass**: 13 release, 14 Flatpak, 15 Snap.
- `git diff --check`: passes.
- No commits or pushes. Compiler/runtime versions remain pinned as before;
  `kotlinx-coroutines-test` now uses the existing coroutines version.

Generated local evidence:

- `shared/build/test-results/jvmTest/TEST-*.xml`
- `shared/build/reports/tests/jvmTest/index.html`
- `shared/build/reports/visual-design/` (labelled component fixtures)
- `/home/worxbend/.hermes/cache/scratch/zephyr-review/final-verified-check.log`
- `/home/worxbend/.hermes/cache/scratch/zephyr-review/final-junit-results.json`

These scratch/build artifacts are local verification outputs, not checked-in data.

## All-findings matrix

All source paths below are under `shared/src`, except desktop composition.
Test class names refer to the corresponding commonTest/jvmTest Kotlin files.

| Finding | Implemented behavior | Passing regression evidence |
| --- | --- | --- |
| F01 | Proxy credentials bind to destination identity and a revision. Changing host/port/username without replacement cannot attach an earlier password; partial commits expose cleanup/recovery state. | `JvmProxyConfigurationServiceTest` — identity changes, replacement/rollback failures, pending cleanup/reload. |
| F02 | `SdkmanProcessSession` owns asynchronous launch, cancellation intent, process groups/descendants, output bounds, stdin EOF, deadlines and bounded cleanup; late launches are terminated. | `SdkmanProcessSessionTest`, `ApacheCommonsSdkmanCommandRunnerTest` — gated launch, TERM-ignoring descendants, inherited pipes, output caps, cancellation. |
| F03 | `SdkmanFilesystemInspector` pins canonical trusted homes while rejecting candidate/root/version symlinks. Discovery, admission and postconditions use containment. | `SdkmanFilesystemSafetyTest`, `JvmSdkmanRepositoryTest` — external ancestors preserved and trusted-home aliases supported. |
| F04 | `OperationCoordinator` owns durable admission and execution independently of presentation reads; accepted requests cannot disappear under the read mutex. Shutdown rejects queued requests explicitly. | `ZephyrViewModelTest.journaledConfirmationCannotBeDroppedByNavigationRead`, `coordinatorQueuesAdmissionAndExplicitlyRejectsQueuedWorkOnShutdown`. |
| F05 | Persist typed execution receipts before independent inventory/detail/catalog refresh. Refresh failure is presentation staleness, not failed execution. | `ZephyrViewModelTest.successfulMutationReceiptSurvivesGatedInventoryFailure`, `mutationDetailRefreshCannotPublishCandidateAIntoFailedRouteB`. |
| F06 | Execution, journal changes and recovery share lifecycle ownership. Live IDs reject recovery before evidence reads; all Task Center recovery/verification controls respect live ownership. | `ZephyrViewModelTest.recoveryRejectsLiveOwnedBatchBeforeAnyReconciliationRead`, `recoveryEvidenceAndExecutionWritesAreSerialized`; live-owned `HermeticFeatureRoutesTest` cases. |
| F07 | Independent V2 ledger codec checks complete document framing, plan count/digest, ordered unique steps, UTF-8 and transaction identity. Typed corrupt/unsupported/unreadable failures preserve originals and block ordinary writes. | `OperationLedgerCodecTest`, `JvmOperationStoreTest`, `AtomicDocumentStoreTest`, `ZephyrViewModelTest.unreadableLedgerIsVisibleAndBlocksAdmissionWithoutSaving`. |
| F08 | Cache replacement stages and forces a sibling temporary file, then atomically replaces the directory entry. It cannot overwrite a symlink target; failures preserve prior bytes. | `JvmCandidateMetadataCacheStoreTest` — symlink target preservation and replacement failure. |
| F09 | Cleanup first-seen observations are removed only for verified candidate evidence, not unknown/failed/unrelated audits. | `ObservationEvidenceTest`, `AppPolicyCoordinatorTest.partialAuditPreservesUnauditedHistoryAndVerifiedResolutionRemovesOnlyItsCandidate`, `blockedSettingsHydrationNeverErasesPersistedObservationHistory`. |
| F10 | Per-step cleanup receipts and indeterminate outcomes remain distinct. Uncertain tasks have a non-live Verify outcome action, independent of replay eligibility; verification never blindly replays uncertain work. | `ZephyrViewModelTest.cleanupRetainsEachVerifiedRemovalReceipt`, `singleOperationsRetainIndeterminateReceipts`, `verifyingIndeterminateOnlyTaskCanConfirmSuccessWithoutReplayingMutation`; actual Task Center uncertainty cases. |
| F11 | Detail reads are cancellation/generation/candidate-identity guarded. Mutation invalidation also fences old audit results and failures; live mutations reject new audit admission. | `ZephyrViewModelTest.mutationDetailRefreshCannotPublishCandidateAIntoFailedRouteB`, `mutationInvalidatesSuspendedAuditBeforeSnapshotOrFindingCanPublish`, `invalidatedAuditFailureCannotClearLiveMutationStateOrPublishActivity`. |
| F12 | Shared pure cleanup eligibility excludes defaults/protection and intersects displayed targets with completed LiveComplete audit evidence. Cards/tables/details agree; metadata alone is not cleanup admission. | `CleanupEligibilityTest`; actual local-only, installed table, unaudited-detail, default-only and partial-evidence `HermeticFeatureRoutesTest` cases. |
| F13 | Bounded, versioned/checksummed whole-settings snapshots use staged chunked Preferences generations; validation precedes writes and failed commits retain a complete previous snapshot. | `SettingsSnapshotCommitTest`, `SettingsPersistenceReliabilityTest` — size boundaries, oversized values, commit failure and strict legacy migration. |
| F14 | Settings retain dirty revisions and expose Loading/Dirty/Saving/Saved/Failed plus retry. Bounded drain requires acknowledgement; desktop blocks normal exit on failure/timeout and permits an explicit unconfirmed exit. | `SettingsWriterLifecycleTest`, `AppSettingsStoreTest`, `AppShutdownCoordinatorTest`, `AppRuntimeTest`, `AppShutdownScreenTest`, actual settings save-status route tests. |
| F15 | Separate credential-store/subprocess boundaries distinguish Found/Missing/Unavailable/Failed. Secret-tool launch/stdin/output/deadline/cleanup are bounded; deletion requires verified Missing readback. | `SecretToolProxyStoreTest`, `JvmProxyConfigurationServiceTest` — blocked/late launch, inherited pipes, false-success deletion, ambiguous writes and cancellation. |
| F16 | One free-text redactor covers ledger persistence, CSV and support exports, including external Unix/Windows/UNC/file-URI paths, spaces and embedded apostrophes. Colon-adjacent paths are covered. | `SensitiveTextRedactorTest`, `OperationLedgerCodecTest`, `JvmOperationJournalExporterTest`, `JvmDiagnosticsExporterTest` — synthetic private-token absence across all sinks. |
| F17 | Nullable checked aggregation keeps missing/unsafe/overflow measurements unknown; no unknown payload is counted as exact zero. | `SdkmanDiskEstimateSafetyTest`, `JvmSdkmanRepositoryTest`, `StorageInventoryTest`. |
| F18 | Runtime-owned completion tracking establishes a hydrated baseline and emits unseen live completions once; policy changes cannot replay observed history. | `OperationCompletionTrackerTest`, `AppPolicyCoordinatorTest.policyTogglesNeverReplayHydratedOrAlreadyObservedCompletions`. |

## Structural acceptance

1. `desktopApp/main.kt` is the production composition root. `AppRuntime` holds
   injected viewmodel/settings/services and coordinates acknowledged shutdown.
2. `application/operation/{OperationCoordinator,OperationExecution,OperationRecovery}`
   owns admitted single/batch/profile/snapshot/cleanup work and recovery. UI route
   disposal cannot own or cancel accepted mutations.
3. Runtime policy owns notifications, evidence-aware observation history and the
   existing opt-in metadata schedule; it is not driven by recomposition.
4. Settings, project import/export, workspace and snapshot presenters own actual
   feature state and coroutine workflows. Screens use injected ports. No empty
   forwarding-only presenter hierarchy or DI framework was introduced.
5. Catalog, maintenance and task screens are split into feature files. Their
   application/audit ownership remains in the UI-facing facade where appropriate,
   rather than pretending that file moves establish new ownership boundaries.
6. The dependency checker scans all production common code without blanket
   suppression. Coroutines tests use suspension gates and injected dispatchers.
   Existing warnings-as-errors, strict dependency verification and packaging gates
   remain enabled; JVM tests isolate preference and fixture roots.

See `ARCHITECTURE.md` for package direction, lifecycle contracts and limitations.

## Deliberate limits and compatibility

- Keep SDKMAN, existing shared/JVM/desktop Gradle modules and constructor injection.
  Domain constructor invariants close invalid-plan/result states without a broad
  sealed-command API migration.
- Preferences supports one application writer, not cross-process transactions.
  If commit and rollback flush both fail, a restart can observe either complete
  generation; mixed reconstructed snapshots are not permitted intentionally.
- Corrupt/unverifiable ledgers need explicit preservation/repair/reset outside an
  ordinary save. V1 single-command history remains supported; unverifiable V1
  variable-length plans are rejected rather than silently shortened.
- Process launch/cleanup remains honest about uninterruptible native startup and
  deliberately detached, unobserved daemons. Adversarial ancestor-rename races
  and process-group escapes are outside the supported threat boundary.
- Formatter adoption is deferred: the measured pinned probe required substantial
  unrelated baseline churn. No formatter baseline or pretend passing gate exists.
  Further file-chooser port extraction/module splits are optional follow-up design,
  not required to close the reviewed defects.
- Tests are hermetic and do not mutate real SDKMAN, use the real vault or publish
  packages. Native installed-package smoke tests, macOS execution, live Secret
  Service integration and store publication were not performed. Desktop compiles;
  the shared memory-runtime/route tests are not a native application E2E suite.
