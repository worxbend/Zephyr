package com.worxbend.zephyr.data

import com.worxbend.zephyr.domain.OperationJournalEntry

interface OperationStore {
    suspend fun load(): List<OperationJournalEntry>

    suspend fun save(entries: List<OperationJournalEntry>)
}

object NoOpOperationStore : OperationStore {
    override suspend fun load(): List<OperationJournalEntry> = emptyList()

    override suspend fun save(entries: List<OperationJournalEntry>) = Unit
}

expect fun createOperationStore(): OperationStore
