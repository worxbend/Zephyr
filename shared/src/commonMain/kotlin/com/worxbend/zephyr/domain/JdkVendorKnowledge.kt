package com.worxbend.zephyr.domain

data class JdkVendorKnowledge(
    val sdkmanCode: String,
    val displayName: String,
    val maintainer: String,
    val summary: String,
    val supportCharacteristics: String,
    val sourceUrl: String,
)

const val JDK_VENDOR_KNOWLEDGE_VERSION = "2026-07-29"

val JDK_VENDOR_KNOWLEDGE: List<JdkVendorKnowledge> =
    listOf(
        JdkVendorKnowledge(
            sdkmanCode = "tem",
            displayName = "Eclipse Temurin",
            maintainer = "Eclipse Adoptium",
            summary = "OpenJDK binaries produced by the Eclipse Adoptium project.",
            supportCharacteristics = "Community binaries; commercial support is available from ecosystem vendors.",
            sourceUrl = "https://adoptium.net/support/",
        ),
        JdkVendorKnowledge(
            sdkmanCode = "zulu",
            displayName = "Azul Zulu",
            maintainer = "Azul",
            summary = "TCK-tested OpenJDK builds across a broad platform and Java-version range.",
            supportCharacteristics = "Free builds are available; paid support and extended timelines depend on Azul plans.",
            sourceUrl = "https://www.azul.com/products/core/",
        ),
        JdkVendorKnowledge(
            sdkmanCode = "amzn",
            displayName = "Amazon Corretto",
            maintainer = "Amazon Web Services",
            summary = "No-cost, multiplatform, production-ready OpenJDK distribution maintained by AWS.",
            supportCharacteristics = "Long-term support includes performance enhancements and security fixes on published timelines.",
            sourceUrl = "https://docs.aws.amazon.com/corretto/latest/corretto-8-ug/what-is-corretto-8.html",
        ),
        JdkVendorKnowledge(
            sdkmanCode = "graal",
            displayName = "GraalVM",
            maintainer = "Oracle and the GraalVM community",
            summary = "High-performance JDK distribution with Graal compilation and Native Image tooling.",
            supportCharacteristics = "Community and Oracle distributions have distinct licenses, components, and support terms.",
            sourceUrl = "https://www.graalvm.org/release-notes/",
        ),
        JdkVendorKnowledge(
            sdkmanCode = "librca",
            displayName = "BellSoft Liberica",
            maintainer = "BellSoft",
            summary = "OpenJDK distribution with standard, full, and lightweight runtime variants.",
            supportCharacteristics = "Community downloads and commercial support are available; timelines vary by Java release.",
            sourceUrl = "https://bell-sw.com/support/",
        ),
        JdkVendorKnowledge(
            sdkmanCode = "ms",
            displayName = "Microsoft Build of OpenJDK",
            maintainer = "Microsoft",
            summary = "OpenJDK binaries compiled, packaged, tested, and serviced by Microsoft.",
            supportCharacteristics = "Commercial support follows Microsoft policy for listed LTS builds and supported environments.",
            sourceUrl = "https://learn.microsoft.com/en-us/java/openjdk/support",
        ),
        JdkVendorKnowledge(
            sdkmanCode = "oracle",
            displayName = "Oracle JDK",
            maintainer = "Oracle",
            summary = "Oracle's Java Development Kit distribution and commercial Java offering.",
            supportCharacteristics = "Licensing and support terms depend on the release and Oracle subscription or entitlement.",
            sourceUrl = "https://www.oracle.com/java/technologies/java-se-support-roadmap.html",
        ),
    ).also { entries ->
        require(entries.distinctBy(JdkVendorKnowledge::sdkmanCode).size == entries.size)
        require(entries.all { it.sourceUrl.startsWith("https://") })
    }

fun jdkVendorKnowledge(code: String?): JdkVendorKnowledge? = JDK_VENDOR_KNOWLEDGE.firstOrNull { it.sdkmanCode == code }
