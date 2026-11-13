package de.kiwi.backgroundTask

import de.kiwi.api.backgroundTask.BackgroundTaskUserDescriptor
import de.kiwi.api.domain.UserDescriptor
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder

/** User implementing [BackgroundTaskUserDescriptor], used as-is for background tasks */
data class TestUser(override val username: String) : BackgroundTaskUserDescriptor {
    override val userId = null
    override val identity = username
    override val authenticated = true
    override val roles = emptySet<String>()
    override val principal = null
}

/**
 * User implementing only [UserDescriptor], *not* [BackgroundTaskUserDescriptor],
 * so it is wrapped in a [BackgroundTaskUserDescriptor.Delegate] for background tasks.
 */
data class TestUserDescriptor(
    override val username: String,
    override val userId: Long? = null,
    override val roles: Set<String> = emptySet(),
) : UserDescriptor {
    override val principal = null
    override val authenticated = true
    override val identity = username
}

/** Jackson mapper with Kotlin support, like the mapper of a Spring Boot application */
fun testObjectMapper(): ObjectMapper = jacksonMapperBuilder().build()
