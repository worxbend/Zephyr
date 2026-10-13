package com.worxbend.zephyr

import com.worxbend.zephyr.domain.JavaVersion
import com.worxbend.zephyr.domain.RemoteAvailability
import kotlin.test.Test
import kotlin.test.assertEquals

class JavaVersionPresentationTest {
    private val versions =
        listOf(
            javaVersion("21.0.5-tem", "21", "Eclipse Temurin"),
            javaVersion("21.0.4-zulu", "21", "Azul Zulu"),
            javaVersion("17.0.13-tem", "17", "Eclipse Temurin"),
        )

    @Test
    fun filtersByIdentifierFeatureVersionAndProvider() {
        assertEquals(listOf("21.0.4-zulu"), versions.filterByQuery("zulu").map { it.identifier })
        assertEquals(2, versions.filterByQuery("21").size)
        assertEquals(versions, versions.filterByQuery("  "))
    }

    @Test
    fun groupsVersionsByTheSelectedDimension() {
        assertEquals(
            mapOf("JDK 21" to versions.take(2), "JDK 17" to versions.drop(2)),
            versions.groupBy(JavaVersionGrouping.FeatureVersion),
        )
        assertEquals(
            mapOf("Eclipse Temurin" to listOf(versions[0], versions[2]), "Azul Zulu" to listOf(versions[1])),
            versions.groupBy(JavaVersionGrouping.Provider),
        )
        assertEquals(mapOf("" to versions), versions.groupBy(JavaVersionGrouping.None))
    }

    private fun javaVersion(
        identifier: String,
        featureVersion: String,
        providerName: String,
    ) = JavaVersion(
        identifier = identifier,
        featureVersion = featureVersion,
        providerCode = null,
        providerName = providerName,
        isInstalled = true,
        isDefault = false,
        remoteAvailability = RemoteAvailability.Available,
    )
}
