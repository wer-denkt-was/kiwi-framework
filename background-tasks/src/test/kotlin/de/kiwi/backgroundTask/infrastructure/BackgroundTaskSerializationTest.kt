package de.kiwi.backgroundTask.infrastructure

import de.kiwi.api.backgroundTask.*
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.backgroundTask.TestUser
import de.kiwi.backgroundTask.TestUserDescriptor
import de.kiwi.backgroundTask.application.BackgroundTaskQueue
import de.kiwi.backgroundTask.application.messaging.BackgroundTaskMessage
import de.kiwi.backgroundTask.testObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.readValue
import tools.jackson.module.kotlin.treeToValue
import java.util.*

/**
 * Requests, users, and queue messages are transferred to workers as JSON, so they must survive a round-trip.
 */
class BackgroundTaskSerializationTest {
    private val objectMapper = testObjectMapper()

    data class ExampleRequest(val message: String, val count: Int, val tags: List<String>) : BackgroundTaskRequest()

    data class CategoryRequest(val categoryName: String) : BackgroundTaskRequest() {
        override val category get() = categoryName
    }

    private inline fun <reified T> roundTrip(value: T): T {
        val json = objectMapper.writeValueAsString(value)
        return objectMapper.readValue<T>(json)
    }

    @Test
    fun requestRoundTrip() {
        val request: BackgroundTaskRequest = ExampleRequest("hello", 3, listOf("a", "b"))
        assertEquals(request, roundTrip(request))
    }

    @Test
    fun requestCategoryIsDerived() {
        val request: BackgroundTaskRequest = CategoryRequest("special")
        val json = objectMapper.writeValueAsString(request)
        val result = objectMapper.readValue<BackgroundTaskRequest>(json)
        assertEquals("special", result.category)
        assert(!json.contains("\"category\"")) { json }
    }

    @Test
    fun descriptorRoundTrip() {
        val descriptor = BackgroundTaskDescriptor(UUID.randomUUID(), ExampleRequest("hello", 1, emptyList()))
        assertEquals(descriptor, roundTrip(descriptor))
    }

    @Test
    fun userRoundTrip() {
        val user: BackgroundTaskUserDescriptor = TestUser("tester")
        assertEquals(user, roundTrip(user))
    }

    @Test
    fun delegateUserRoundTrip() {
        val delegateUser = TestUserDescriptor("tester", userId = 7, roles = setOf("ADMIN"))
        val taskUser = BackgroundTaskUserDescriptor.delegateOf(delegateUser)
        val result = roundTrip(taskUser)
        assertEquals(taskUser, result)
        assertEquals(7L, result.userId)
        assertEquals(setOf("ADMIN"), result.roles)
    }

    /** User descriptor holding credentials, which must not be serialized by the [BackgroundTaskUserDescriptor.Delegate] */
    data class CredentialsUserDescriptor(
        override val username: String,
        override val principal: Any? = null,
        val password: String? = null,
        val accessToken: String? = null,
        val department: String? = null,
    ) : UserDescriptor {
        override val userId = null
        override val authenticated = true
        override val identity get() = username
        override val roles = emptySet<String>()
    }

    @Test
    fun delegateUserExcludesSensitiveProperties() {
        val delegateUser = CredentialsUserDescriptor(
            username = "tester",
            principal = mapOf("jwt" to "secret-jwt"),
            password = "secret-password",
            accessToken = "secret-token",
            department = "sales",
        )
        val taskUser = BackgroundTaskUserDescriptor.delegateOf(delegateUser)
        val json = objectMapper.writeValueAsString(taskUser)
        assert(!json.contains("secret")) { json }

        val result = roundTrip(taskUser)
        assertEquals("tester", result.username)
        assertEquals(null, result.principal)
        val resultDelegate = (result as BackgroundTaskUserDescriptor.Delegate).delegate as CredentialsUserDescriptor
        assertEquals(CredentialsUserDescriptor("tester", department = "sales"), resultDelegate)
    }

    @Test
    fun wellKnownUsersRoundTrip() {
        for (user in listOf(UserDescriptor.SYSTEM, UserDescriptor.ANONYMOUS)) {
            val taskUser = BackgroundTaskUserDescriptor.delegateOf(user)
            val result = roundTrip(taskUser)
            assertEquals(taskUser, result)
            assertEquals(user.username, result.username)
            assertEquals(user.authenticated, result.authenticated)
            assertEquals(user, result.identity)
        }
    }

    @Test
    fun launchTaskBodyRoundTrip() {
        val body = LaunchTaskBody(
            task = BackgroundTaskDescriptor(UUID.randomUUID(), ExampleRequest("hello", 1, emptyList())),
            user = BackgroundTaskUserDescriptor.delegateOf(TestUserDescriptor("tester")),
        )
        assertEquals(body, roundTrip(body))
    }

    @Test
    fun taskControlRoundTrip() {
        val body = TaskControlBody(UUID.randomUUID(), TestUser("tester"), TaskControlOperation.Cancel)
        assertEquals(body, roundTrip(body))

        val response = TaskControlResponse(body.taskHandle, true, objectMapper.valueToTree(true))
        val result = roundTrip(response)
        assertEquals(response, result)
        assertEquals(true, objectMapper.treeToValue<Boolean>(result.responseData))
    }

    @Test
    fun statusMessageIncludesTypeAndStatus() {
        val message = BackgroundTaskMessage.StatusChanged.Started(UUID.randomUUID(), attempt = 2)
        val json = objectMapper.readTree(objectMapper.writeValueAsString(message))
        assertEquals("Started", json["messageType"].asString())
        assertEquals("Running", json["status"].asString())
        assertEquals(2, json["attempt"].asInt())
        assertEquals(message.taskHandle.toString(), json["taskHandle"].asString())
    }

    @Test
    fun failedMessageIncludesError() {
        val exception = BackgroundTaskQueue.UnknownTaskCategory("unknown")
        val message = BackgroundTaskMessage.StatusChanged.Failed(UUID.randomUUID(), BackgroundTaskError.of(exception))
        val json = objectMapper.readTree(objectMapper.writeValueAsString(message))
        assertEquals("Failed", json["messageType"].asString())
        assertEquals("unknown-task-category", json["error"]["message"].asString())
        assertEquals("unknown", json["error"]["arguments"]["category"].asString())
        assertEquals(BackgroundTaskQueue.UnknownTaskCategory::class.java.name, json["error"]["errorType"].asString())
    }

    @Test
    fun userMessagesIncludeType() {
        val taskHandle = UUID.randomUUID()
        val stage = BackgroundTaskProgress.Stage("copy", index = 1, count = 3, completedItems = 2, totalItems = 8)
        val progressMessage = BackgroundTaskMessage.Progress(taskHandle, BackgroundTaskProgress(42, stage))
        val progressMessageJson = objectMapper.writeValueAsString(progressMessage)
        val progressJson = objectMapper.readTree(progressMessageJson)
        assertEquals("Progress", progressJson["messageType"].asString())
        assertEquals(42, progressJson["progress"]["percent"].asInt())
        assertEquals("copy", progressJson["progress"]["stage"]["name"].asString())
        assertEquals(8, progressJson["progress"]["stage"]["totalItems"].asInt())

        val customMessage = BackgroundTaskMessage.Custom(taskHandle, "itemProcessed", mapOf("item" to 7))
        val customMessageJson = objectMapper.writeValueAsString(customMessage)
        val customJson = objectMapper.readTree(customMessageJson)
        assertEquals("itemProcessed", customJson["messageType"].asString())
        assertEquals(7, customJson["details"]["item"].asInt())
    }
}
