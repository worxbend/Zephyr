package com.worxbend.zephyr.quality

import com.worxbend.zephyr.JavaVersionSort
import com.worxbend.zephyr.JavaVersionStatusFilter
import com.worxbend.zephyr.buildGlobalSearchIndex
import com.worxbend.zephyr.domain.Candidate
import com.worxbend.zephyr.domain.CandidateKind
import com.worxbend.zephyr.domain.CandidateVersion
import com.worxbend.zephyr.domain.JavaVersion
import com.worxbend.zephyr.domain.RemoteAvailability
import com.worxbend.zephyr.domain.StorageMeasurement
import com.worxbend.zephyr.filterAndSort
import com.worxbend.zephyr.sdkman.measureStorageDirectory
import com.worxbend.zephyr.searchGlobalIndex
import com.worxbend.zephyr.storage.UserDocumentFiles
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import java.util.Locale

/** Report-only synthetic baselines: no SDKMAN commands, network, GUI, or timing assertions. */
fun main() {
    println("java=${System.getProperty("java.version")} os=${System.getProperty("os.name")} arch=${System.getProperty("os.arch")}")
    println("probe\tmedian_ms\tp90_ms\tsamples")
    val versions =
        List(10_000) { index ->
            JavaVersion("${17 + index % 9}.0.$index-tem", "${17 + index % 9}", "tem", "Temurin", true, false, RemoteAvailability.Available)
        }
    probe("filter_sort_10000_versions") {
        versions.filterAndSort("tem", JavaVersionStatusFilter.All, null, JavaVersionSort.Version).size.toLong()
    }
    val candidates =
        List(50) { index ->
            Candidate(
                name = "sdk$index",
                displayName = "SDK fixture $index",
                kind = CandidateKind.Sdk,
                installedVersions = List(200) { CandidateVersion("1.$it", true, false, RemoteAvailability.Available) },
                defaultVersion = null,
                hasLocalOnlyVersions = false,
                localOnlyVersionCount = 0,
                localOnlyVersions = emptyList(),
            )
        }
    val searchIndex = buildGlobalSearchIndex(candidates, emptyList())
    probe("search_${searchIndex.size}_entries") { searchGlobalIndex(searchIndex, "sdk").size.toLong() }
    val directory = Files.createTempDirectory("zephyr-performance-")
    try {
        val tree = Files.createDirectory(directory.resolve("inventory"))
        repeat(20) { branch ->
            val child = Files.createDirectory(tree.resolve("sdk$branch"))
            repeat(50) { Files.write(child.resolve("file$it"), ByteArray(256)) }
        }
        probe("inventory_1000_files") {
            val measurement = measureStorageDirectory(FileSystem.SYSTEM, tree.toOkioPath())
            check(measurement is StorageMeasurement.Exact && measurement.bytes == 256_000L)
            measurement.bytes
        }
        val document = directory.resolve("document.json")
        Files.writeString(document, " ".repeat(512 * 1024))
        val documents = UserDocumentFiles()
        probe("bounded_read_512_kib") { documents.read(document).length.toLong() }
    } finally {
        directory.toFile().deleteRecursively().also { check(it) { "Performance fixture cleanup failed." } }
    }
    println("checksum=$checksum")
}

private var checksum = 0L

private fun probe(
    name: String,
    action: () -> Long,
) {
    repeat(10) { checksum += action() }
    val elapsed =
        List(20) {
            val start = System.nanoTime()
            checksum += action()
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
    println(String.format(Locale.ROOT, "%s\t%.3f\t%.3f\t%d", name, elapsed[elapsed.size / 2], elapsed[17], elapsed.size))
}
