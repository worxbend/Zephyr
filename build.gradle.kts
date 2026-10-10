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
        environment("TMPDIR", scratch.get().asFile.apply { mkdirs() }.absolutePath)
    }
}

tasks.named("check") {
    dependsOn(architectureCheck, architectureCheckerTest, ":shared:check", ":desktopApp:check")
}
