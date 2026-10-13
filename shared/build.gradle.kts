import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    jacoco
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm()

    compilerOptions {
        allWarningsAsErrors.set(true)
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.compose.uiTest)
            implementation(libs.kotlinx.coroutinesTest)
        }
        jvmMain.dependencies {
            implementation(libs.commons.exec)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}

jacoco {
    toolVersion = "0.8.15"
}

val jvmCoverageReport by tasks.registering(JacocoReport::class) {
    group = "verification"
    description = "Reports measured shared JVM line and branch coverage without inventing a threshold."
    dependsOn("jvmTest")
    executionData(layout.buildDirectory.file("jacoco/jvmTest.exec"))
    classDirectories.from(layout.buildDirectory.dir("classes/kotlin/jvm/main"))
    sourceDirectories.from(files("src/commonMain/kotlin", "src/jvmMain/kotlin"))
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(true)
    }
}

tasks.named("check") { dependsOn(jvmCoverageReport) }

val testCompilation =
    kotlin.targets
        .getByName("jvm")
        .compilations
        .getByName("test")
tasks.register<JavaExec>("performanceProbe") {
    val performanceTemp =
        layout.buildDirectory
            .dir("tmp/performanceProbe")
            .get()
            .asFile
    group = "verification"
    description = "Report synthetic filtering, search, bounded-read, and inventory baselines without timing gates."
    dependsOn("jvmTestClasses")
    classpath = files(testCompilation.output.allOutputs, testCompilation.runtimeDependencyFiles)
    mainClass.set("com.worxbend.zephyr.quality.PerformanceProbeKt")
    systemProperty("java.io.tmpdir", performanceTemp.absolutePath)
    environment("TMPDIR", performanceTemp.absolutePath)
    doFirst { performanceTemp.mkdirs() }
}

// Tests must never consult or mutate the developer's ambient preference tree.
tasks.withType<Test>().configureEach {
    val fixtureRoot =
        layout.buildDirectory
            .dir("test-fixtures")
            .get()
            .asFile
    val preferencesRoot =
        layout.buildDirectory
            .dir("test-preferences")
            .get()
            .asFile
    systemProperty("java.util.prefs.userRoot", preferencesRoot.absolutePath)
    systemProperty("java.io.tmpdir", fixtureRoot.absolutePath)
    // Shell fixtures and no-follow filesystem tests need the same task-owned root.
    environment("TMPDIR", fixtureRoot.absolutePath)
    doFirst {
        fixtureRoot.mkdirs()
        preferencesRoot.mkdirs()
    }
}
