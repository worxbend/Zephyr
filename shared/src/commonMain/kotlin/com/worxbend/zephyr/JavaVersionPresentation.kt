package com.worxbend.zephyr

import com.worxbend.zephyr.domain.JavaVersion

internal enum class JavaVersionGrouping(
    val label: String,
) {
    None("None"),
    FeatureVersion("By Version"),
    Provider("By Provider"),
}

internal enum class JavaVersionStatusFilter(
    val label: String,
) {
    All("All"),
    Installed("Installed"),
    Available("Available"),
    LocalOnly("Local-only"),
}

internal enum class JavaVersionSort(
    val label: String,
) {
    Catalog("Catalog"),
    Version("Version"),
    Vendor("Vendor"),
}

internal fun List<JavaVersion>.filterByQuery(query: String): List<JavaVersion> =
    if (query.isBlank()) {
        this
    } else {
        filter { version ->
            version.identifier.contains(query, ignoreCase = true) ||
                version.featureVersion.contains(query, ignoreCase = true) ||
                version.providerName.orEmpty().contains(query, ignoreCase = true)
        }
    }

internal fun List<JavaVersion>.filterAndSort(
    query: String,
    status: JavaVersionStatusFilter,
    providerCode: String?,
    sort: JavaVersionSort,
): List<JavaVersion> =
    filterByQuery(query)
        .filter { version ->
            when (status) {
                JavaVersionStatusFilter.All -> true
                JavaVersionStatusFilter.Installed -> version.isInstalled
                JavaVersionStatusFilter.Available -> version.isRemoteAvailable
                JavaVersionStatusFilter.LocalOnly -> version.isConfirmedLocalOnly
            }
        }.filter { providerCode == null || it.providerCode == providerCode }
        .let { filtered ->
            when (sort) {
                JavaVersionSort.Catalog -> {
                    filtered
                }

                JavaVersionSort.Version -> {
                    filtered.sortedByDescending(JavaVersion::identifier)
                }

                JavaVersionSort.Vendor -> {
                    filtered.sortedWith(
                        compareBy<JavaVersion> { it.providerName.orEmpty() }
                            .thenByDescending { it.identifier },
                    )
                }
            }
        }

internal fun List<JavaVersion>.groupBy(grouping: JavaVersionGrouping): Map<String, List<JavaVersion>> =
    when (grouping) {
        JavaVersionGrouping.None -> mapOf("" to this)
        JavaVersionGrouping.FeatureVersion -> groupBy { "JDK ${it.featureVersion}" }
        JavaVersionGrouping.Provider -> groupBy { it.providerName ?: "Other Providers" }
    }
