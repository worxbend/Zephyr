package com.worxbend.zephyr.runtime

import com.worxbend.zephyr.domain.OperationJournalEntry

/** Hydration establishes a baseline; only completions observed afterwards are live events. */
internal class OperationCompletionTracker {
    private var hydrated = false
    private val observedCompletions = mutableSetOf<Long>()

    fun observe(entries: List<OperationJournalEntry>): List<OperationJournalEntry> {
        val completed = entries.filter { it.completedAtEpochMillis != null }
        if (!hydrated) {
            hydrated = true
            observedCompletions.addAll(completed.map(OperationJournalEntry::id))
            return emptyList()
        }
        return completed
            .filter { observedCompletions.add(it.id) }
            .sortedWith(compareBy(OperationJournalEntry::completedAtEpochMillis, OperationJournalEntry::id))
    }
}
