package com.worxbend.zephyr.features.catalog

import com.worxbend.zephyr.domain.SdkmanTransaction

/** Reviewed commands and protection edits go to the application owner, not a screen-owned executor. */
data class CatalogActions(
    val review: (SdkmanTransaction) -> Unit,
    val protect: (String, String, Boolean) -> Unit,
)
