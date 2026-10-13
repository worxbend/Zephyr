package com.worxbend.zephyr.domain

private val CANDIDATE_NAME_PATTERN = Regex("[a-z0-9][a-z0-9-]*")
private val VERSION_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._+-]*")
private const val MAX_VERSION_LENGTH = 256

internal fun isValidSdkmanCandidateName(value: String): Boolean = value.matches(CANDIDATE_NAME_PATTERN)

internal fun isValidSdkmanVersion(value: String): Boolean = value.length <= MAX_VERSION_LENGTH && value.matches(VERSION_PATTERN)
