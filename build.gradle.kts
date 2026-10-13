import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    base
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
}

val architectureCheck by tasks.registering(Exec::class) {
    group = "verification"
    description = "Enforces common Kotlin package and platform dependency boundaries."
    workingDir(rootDir)
    commandLine("python3", "-B", "tools/architecture_check.py", "--root", rootDir.absolutePath)
}

val architectureCheckerTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs hermetic regressions for the architecture boundary checker."
    workingDir(rootDir)
    commandLine("python3", "-B", "-m", "unittest", "discover", "-s", "tools", "-p", "test_architecture_check.py", "-v")
    val scratch = layout.buildDirectory.dir("architecture-tests/scratch")
    doFirst {
        environment(
            "TMPDIR",
            scratch
                .get()
                .asFile
                .apply { mkdirs() }
                .absolutePath,
        )
    }
}

val siteBuilderTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs hermetic release metadata and site output safety regressions."
    workingDir(rootDir)
    commandLine("python3", "-B", "-m", "unittest", "discover", "-s", "tools", "-p", "test_site_build.py", "-v")
    val scratch = layout.buildDirectory.dir("site-builder-tests/scratch")
    doFirst {
        environment(
            "TMPDIR",
            scratch
                .get()
                .asFile
                .apply { mkdirs() }
                .absolutePath,
        )
    }
}

val dependencyAuditTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks dependency inventory parsing and fail-closed OSV response handling without network IO."
    workingDir(rootDir)
    commandLine("python3", "-B", "-m", "unittest", "discover", "-s", "tools", "-p", "test_audit_dependencies.py", "-v")
}

val ktlint by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies.add(ktlint.name, "com.pinterest.ktlint:ktlint-cli:1.8.0:all")

val kotlinFormatCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks all Kotlin sources and build scripts with pinned, Compose-aware ktlint."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir(rootDir)
    args(
        "--reporter=plain",
        "shared/src/**/*.kt",
        "desktopApp/src/**/*.kt",
        "*.gradle.kts",
        "shared/*.gradle.kts",
        "desktopApp/*.gradle.kts",
    )
}

val kotlinFormat by tasks.registering(JavaExec::class) {
    group = "formatting"
    description = "Formats Kotlin sources and build scripts; review this separately from behavioral changes."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir(rootDir)
    args("--format", "shared/src/**/*.kt", "desktopApp/src/**/*.kt", "*.gradle.kts", "shared/*.gradle.kts", "desktopApp/*.gradle.kts")
}

val dependencyInventory by tasks.registering {
    group = "verification"
    description = "Exports resolved runtime, test, tooling, and build-plugin coordinates for advisory review."
    notCompatibleWithConfigurationCache("Resolves the selected configuration graphs at execution time.")
    val report = layout.buildDirectory.file("reports/dependencies/inventory.tsv")
    outputs.file(report)
    outputs.upToDateWhen { false }
    doLast {
        val rows = sortedSetOf<String>()
        allprojects.forEach { owner ->
            val names =
                setOf(
                    "jvmCompileClasspath",
                    "jvmRuntimeClasspath",
                    "jvmTestCompileClasspath",
                    "jvmTestRuntimeClasspath",
                    "compileClasspath",
                    "runtimeClasspath",
                    "testCompileClasspath",
                    "testRuntimeClasspath",
                    "jacocoAgent",
                    "jacocoAnt",
                    "ktlint",
                )
            val selected =
                owner.configurations.filter { it.isCanBeResolved && it.name in names } +
                    owner.buildscript.configurations.filter { it.isCanBeResolved && it.name == "classpath" }
            selected.forEach { configuration ->
                configuration.incoming.resolutionResult.allComponents.forEach { component ->
                    val id = component.id as? ModuleComponentIdentifier
                    if (id != null) rows += "${id.group}\t${id.module}\t${id.version}\t${owner.path}:${configuration.name}"
                }
            }
        }
        val destination = report.get().asFile
        destination.parentFile.mkdirs()
        destination.writeText(rows.joinToString("\n", postfix = "\n"))
        println("Dependency inventory: ${rows.size} coordinate/scope entries in $destination")
    }
}

tasks.named("check") {
    dependsOn(
        architectureCheck,
        architectureCheckerTest,
        siteBuilderTest,
        dependencyAuditTest,
        kotlinFormatCheck,
        ":shared:check",
        ":desktopApp:check",
    )
}
