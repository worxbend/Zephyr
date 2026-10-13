package com.worxbend.zephyr

import com.worxbend.zephyr.domain.JavaVersion
import com.worxbend.zephyr.domain.RemoteAvailability
import kotlin.test.Test
import kotlin.test.assertEquals

class JavaVersionFilterTest {
    @Test
    fun combinesStatusVendorQueryAndSort() {
        val versions =
            listOf(
                javaVersion("21.0.5-tem", "tem", "Eclipse Temurin", installed = true, available = true),
                javaVersion("17.0.12-zulu", "zulu", "Azul Zulu", installed = true, available = false),
                javaVersion("22.0.1-tem", "tem", "Eclipse Temurin", installed = false, available = true),
            )

        val filtered =
            versions.filterAndSort(
                query = "tem",
                status = JavaVersionStatusFilter.Available,
                providerCode = "tem",
                sort = JavaVersionSort.Version,
            )

        assertEquals(listOf("22.0.1-tem", "21.0.5-tem"), filtered.map { it.identifier })
    }

    private fun javaVersion(
        identifier: String,
        providerCode: String,
        providerName: String,
        installed: Boolean,
        available: Boolean,
    ) = JavaVersion(
        identifier = identifier,
        featureVersion = identifier.substringBefore('.'),
        providerCode = providerCode,
        providerName = providerName,
        isInstalled = installed,
        isDefault = false,
        remoteAvailability = if (available) RemoteAvailability.Available else RemoteAvailability.LocalOnly,
    )
}
