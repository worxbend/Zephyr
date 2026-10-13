package com.worxbend.zephyr

import com.worxbend.zephyr.data.isValidHttpsUrl
import com.worxbend.zephyr.domain.isValidSdkmanVersion
import com.worxbend.zephyr.domain.javaProviderCode

internal fun releaseNotesUrl(
    candidate: String,
    targetVersion: String,
): String? {
    if (!isValidSdkmanVersion(targetVersion)) return null
    val url =
        when (candidate) {
            "java" -> {
                when (javaProviderCode(targetVersion)) {
                    "tem" -> "https://adoptium.net/temurin/release-notes/"
                    "zulu" -> "https://docs.azul.com/core/release-notes"
                    "amzn" -> "https://docs.aws.amazon.com/corretto/latest/corretto-8-ug/change-log.html"
                    "graal" -> "https://www.graalvm.org/release-notes/"
                    "librca" -> "https://bell-sw.com/pages/liberica-release-notes/"
                    "ms" -> "https://github.com/microsoft/openjdk/releases"
                    "oracle" -> "https://www.oracle.com/java/technologies/javase/jdk-relnotes-index.html"
                    else -> null
                }
            }

            "gradle" -> {
                "https://docs.gradle.org/$targetVersion/release-notes.html"
            }

            "kotlin" -> {
                "https://github.com/JetBrains/kotlin/releases"
            }

            "maven" -> {
                "https://maven.apache.org/docs/history.html"
            }

            "scala" -> {
                "https://github.com/scala/scala/releases"
            }

            "groovy" -> {
                "https://groovy-lang.org/changelogs.html"
            }

            "sbt" -> {
                "https://github.com/sbt/sbt/releases"
            }

            else -> {
                null
            }
        }
    return url?.takeIf(::isValidHttpsUrl)
}
