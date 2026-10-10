package com.worxbend.zephyr.settings

import com.worxbend.zephyr.domain.DesiredCandidateState
import com.worxbend.zephyr.domain.DesiredStateSourceKind
import com.worxbend.zephyr.domain.DesiredToolchainState
import com.worxbend.zephyr.domain.InstallTarget
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64

/** Complete, bounded, versioned snapshot. Unlike migration decoding, v1 never drops records. */
internal object AppSettingsCodec {
    const val MAX_DOCUMENT_LENGTH = 1_048_576
    private const val HEADER = "zephyr-settings:1"
    private const val MAX_ITEMS = 8_192

    fun encode(settings: AppSettings): String {
        val bytes = BoundedOutput()
        DataOutputStream(bytes).use { output ->
            with(output) {
                writeString(settings.themePreference.name)
                writeString(settings.uiDensity.name)
                writeString(settings.textScale.name)
                writeString(settings.motionPreference.name)
                writeString(settings.metadataRefreshSchedule.name)
                writeString(settings.updateNotificationPolicy.name)
                writeString(settings.operationNotificationPolicy.name)
                writeString(settings.cleanupGracePeriod.name)
                writeItems(settings.localOnlyObservations) {
                    writeString(it.candidate); writeString(it.version); writeLong(it.firstSeenEpochMillis)
                }
                writeBoolean(settings.showSdkmanHome)
                writeItems(settings.favoriteCandidates) { writeString(it) }
                writeItems(settings.favoriteJdkVendors) { writeString(it) }
                writeItems(settings.recentCandidates) { writeString(it) }
                writeItems(settings.toolchainProfiles) { profile ->
                    writeString(profile.name)
                    writeItems(profile.targets) { writeString(it.candidate); writeString(it.version) }
                }
                writeItems(settings.projectWorkspaces) { writeString(it.sdkmanRcPath); writeString(it.displayName) }
                val desired = settings.desiredToolchainState
                writeBoolean(desired != null)
                if (desired != null) {
                    writeInt(desired.schemaVersion); writeString(desired.sourceKind.name); writeString(desired.sourceLabel)
                    writeItems(desired.candidates) {
                        writeString(it.candidate)
                        writeBoolean(it.defaultVersion != null)
                        it.defaultVersion?.let { version -> writeString(version) }
                        writeItems(it.installedVersions) { version -> writeString(version) }
                    }
                }
                writeInt(settings.navigationWidthDp)
                writeString(settings.installedViewMode.name); writeString(settings.catalogViewMode.name)
                writeItems(settings.savedJdkFilters) {
                    writeString(it.name); writeString(it.query); writeString(it.status)
                    writeBoolean(it.providerCode != null)
                    it.providerCode?.let { provider -> writeString(provider) }
                    writeString(it.sort)
                }
            }
        }
        val payload = bytes.toByteArray()
        val document = "$HEADER\n${digest(payload)}\n${Base64.getEncoder().encodeToString(payload)}"
        require(document.length <= MAX_DOCUMENT_LENGTH) { "Settings snapshot exceeds the persistence limit." }
        // Ensure newly accepted data can be read without a partial/default interpretation.
        require(decode(document) == settings) { "Settings snapshot cannot be represented without loss." }
        return document
    }

    fun decode(document: String): AppSettings {
        require(document.length <= MAX_DOCUMENT_LENGTH) { "Settings snapshot exceeds the persistence limit." }
        val fields = document.split('\n')
        require(fields.size == 3 && fields[0] == HEADER) { "Unsupported or corrupt settings snapshot." }
        val bytes = Base64.getDecoder().decode(fields[2])
        require(Base64.getEncoder().encodeToString(bytes) == fields[2]) { "Noncanonical or truncated settings snapshot." }
        require(fields[1] == digest(bytes)) { "Settings snapshot integrity check failed." }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            with(input) {
                val settings = AppSettings(
                    themePreference = enumValueOf(readUTF()),
                    uiDensity = enumValueOf(readUTF()),
                    textScale = enumValueOf(readUTF()),
                    motionPreference = enumValueOf(readUTF()),
                    metadataRefreshSchedule = enumValueOf(readUTF()),
                    updateNotificationPolicy = enumValueOf(readUTF()),
                    operationNotificationPolicy = enumValueOf(readUTF()),
                    cleanupGracePeriod = enumValueOf(readUTF()),
                    localOnlyObservations = readItems {
                        LocalOnlyObservation(readUTF(), readUTF(), readLong()).also {
                            require(it.candidate.isNotBlank() && it.version.isNotBlank() && it.firstSeenEpochMillis >= 0)
                        }
                    },
                    showSdkmanHome = readBoolean(),
                    favoriteCandidates = readItems(::readUTF).toSet(),
                    favoriteJdkVendors = readItems(::readUTF).toSet(),
                    recentCandidates = readItems(::readUTF),
                    toolchainProfiles = readItems {
                        ToolchainProfile(readUTF(), readItems { InstallTarget(readUTF(), readUTF()) })
                    },
                    projectWorkspaces = readItems { ProjectWorkspaceReference(readUTF(), readUTF()) },
                    desiredToolchainState = if (readBoolean()) DesiredToolchainState(
                        schemaVersion = readInt(),
                        sourceKind = DesiredStateSourceKind.valueOf(readUTF()),
                        sourceLabel = readUTF(),
                        candidates = readItems {
                            DesiredCandidateState(readUTF(), if (readBoolean()) readUTF() else null, readItems(::readUTF))
                        },
                    ) else null,
                    navigationWidthDp = readInt(),
                    installedViewMode = enumValueOf(readUTF()),
                    catalogViewMode = enumValueOf(readUTF()),
                    savedJdkFilters = readItems {
                        SavedJdkFilter(readUTF(), readUTF(), readUTF(), if (readBoolean()) readUTF() else null, readUTF())
                    },
                )
                require(available() == 0) { "Trailing settings snapshot data." }
                settings
            }
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        require(value.length <= 8_192) { "Settings field exceeds the persistence limit." }
        writeUTF(value)
    }

    private class BoundedOutput : ByteArrayOutputStream() {
        override fun write(value: Int) {
            require(count < MAX_DOCUMENT_LENGTH) { "Settings snapshot exceeds the persistence limit." }
            super.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(length <= MAX_DOCUMENT_LENGTH - count) { "Settings snapshot exceeds the persistence limit." }
            super.write(bytes, offset, length)
        }
    }

    private fun digest(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun <T> DataOutputStream.writeItems(items: Collection<T>, write: (T) -> Unit) {
        require(items.size <= MAX_ITEMS) { "Too many settings records." }
        writeInt(items.size)
        items.forEach(write)
    }

    private fun <T> DataInputStream.readItems(read: () -> T): List<T> {
        val count = readInt()
        require(count in 0..MAX_ITEMS) { "Invalid settings record count." }
        return List(count) { read() }
    }
}
