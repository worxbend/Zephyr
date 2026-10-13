package com.worxbend.zephyr.data

import com.worxbend.zephyr.sdkman.ApacheCommonsSdkmanCommandRunner
import com.worxbend.zephyr.sdkman.JvmCandidateMetadataCacheStore
import com.worxbend.zephyr.sdkman.JvmSdkmanRepository
import com.worxbend.zephyr.sdkman.PreferencesProtectedVersionStore
import okio.FileSystem
import okio.Path.Companion.toPath

actual fun createSdkmanRepository(): SdkmanRepository {
    val proxy = JvmProxyConfigurationService()
    val sdkmanHome = JvmSdkmanHomeConfigurationService()
    return JvmSdkmanRepository(
        fileSystem = FileSystem.SYSTEM,
        sdkmanHomeResolver = { sdkmanHome.resolveHome().toString().toPath() },
        protectedVersionStore = PreferencesProtectedVersionStore(),
        candidateCacheStore = JvmCandidateMetadataCacheStore(),
        commandRunnerFactory = { home -> ApacheCommonsSdkmanCommandRunner(home, proxy::environment) },
    )
}
