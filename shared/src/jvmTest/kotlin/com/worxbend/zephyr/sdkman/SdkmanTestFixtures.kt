package com.worxbend.zephyr.sdkman

import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.AbstractPreferences

internal fun sdkmanTestDirectory(prefix: String): Path =
    Files.createTempDirectory(
        Path.of(System.getenv("TMPDIR") ?: System.getenv("RUNNER_TEMP") ?: System.getProperty("java.io.tmpdir")),
        prefix,
    ).toRealPath()

internal class SdkmanMemoryPreferences(parent: AbstractPreferences? = null, name: String = "") : AbstractPreferences(parent, name) {
    private val values = mutableMapOf<String, String>()
    private val children = mutableMapOf<String, SdkmanMemoryPreferences>()
    override fun putSpi(key: String, value: String) { values[key] = value }
    override fun getSpi(key: String): String? = values[key]
    override fun removeSpi(key: String) { values.remove(key) }
    override fun removeNodeSpi() { values.clear(); children.clear() }
    override fun keysSpi(): Array<String> = values.keys.toTypedArray()
    override fun childrenNamesSpi(): Array<String> = children.keys.toTypedArray()
    override fun childSpi(name: String): AbstractPreferences = children.getOrPut(name) { SdkmanMemoryPreferences(this, name) }
    override fun syncSpi() = Unit
    override fun flushSpi() = Unit
}
