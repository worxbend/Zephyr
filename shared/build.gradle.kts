plugins {
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

// Tests must never consult or mutate the developer's ambient preference tree.
tasks.withType<Test>().configureEach {
    val fixtureRoot = layout.buildDirectory.dir("test-fixtures").get().asFile
    val preferencesRoot = layout.buildDirectory.dir("test-preferences").get().asFile
    systemProperty("java.util.prefs.userRoot", preferencesRoot.absolutePath)
    systemProperty("java.io.tmpdir", fixtureRoot.absolutePath)
    doFirst {
        fixtureRoot.mkdirs()
        preferencesRoot.mkdirs()
    }
}
