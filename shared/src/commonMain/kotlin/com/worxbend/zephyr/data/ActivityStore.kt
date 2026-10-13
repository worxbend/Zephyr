package com.worxbend.zephyr.data

import com.worxbend.zephyr.domain.ActivityEvent

interface ActivityStore {
    suspend fun load(): List<ActivityEvent>

    suspend fun save(events: List<ActivityEvent>)
}

object NoOpActivityStore : ActivityStore {
    override suspend fun load(): List<ActivityEvent> = emptyList()

    override suspend fun save(events: List<ActivityEvent>) = Unit
}

expect fun createActivityStore(): ActivityStore
