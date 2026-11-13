package de.kiwi.api.backgroundTask

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonTypeInfo
import de.kiwi.api.domain.UserDescriptor
import org.springframework.core.convert.converter.Converter
import java.io.Serializable
import com.fasterxml.jackson.annotation.JsonTypeInfo.As as JsonTypeAs
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id as JsonTypeId

/**
 * Specialized [UserDescriptor][de.kiwi.api.domain.UserDescriptor] passed to [BackgroundTaskHandler] methods
 * identifying the user that launched the background task.
 * This UserDescriptor must be polymorphically serializable/deserializable by Jackson to enable transfer of the user
 * to the background task worker, representing the user on another process instance.
 *
 * A [UserDescriptor] passed to [BackgroundTaskService.launch][de.kiwi.api.application.service.BackgroundTaskService.launch]
 * is used as-is if it implements this interface. Otherwise, it is converted by the application's `ConversionService`
 * if it supports the conversion (see [BackgroundTaskUserConverter]), or wrapped in a [Delegate] as a fallback.
 *
 * **Serialization of credentials:** The user is stored with the task in the database, and sent in queue messages.
 * Implementations must not serialize credentials, tokens or other secrets (see [Delegate] for the fallback).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(property = "@class", use = JsonTypeId.CLASS, include = JsonTypeAs.PROPERTY)
interface BackgroundTaskUserDescriptor : UserDescriptor, Serializable {

    /**
     * Simple [BackgroundTaskUserDescriptor] implementation which delegates the [UserDescriptor]
     * implementation to a [delegate] instance, which is serialized/deserialized and its class name is
     * included in JSON in the `@delegateClass` property.
     * - This implementation *only* works if the [delegate] UserDescriptor is serializable/deserializable by Jackson.
     * - Properties of [UserDescriptor] are not serialized for the outer delegate instance
     *
     * **Serialization of credentials:** The [delegate] is serialized with all its properties, and stored with the
     * task in the database and sent in queue messages (e.g. retained in RabbitMQ's dead-letter queue). Well-known
     * sensitive top-level properties of the delegate ([UserDescriptor.principal], `credentials`, `password`, `token`,
     * `accessToken`, `refreshToken`, `idToken`, `secret`, `apiKey`, `authentication`) are excluded, both when
     * serialized and deserialized, so the delegate must accept them missing (e.g. nullable, or with a default value).
     * Other properties, and nested properties, are serialized as they are: A user descriptor holding credentials,
     * tokens or other secrets in any other property must not be wrapped in a [Delegate]; register a
     * [BackgroundTaskUserConverter] converting it to a [BackgroundTaskUserDescriptor] without secrets instead.
     */
    @JsonIgnoreProperties("userId", "identity", "username", "authenticated", "roles", "principal", "isAdmin")
    data class Delegate(
        /** Class name of the [delegate], determining its type when deserialized */
        @field:JsonProperty("@delegateClass", required = true)
        val delegateClassName: String,

        /** User descriptor implementing the [UserDescriptor] properties, excluding well-known sensitive properties */
        @field:JsonIgnoreProperties(
            value = [
                "principal", "credentials", "password", "token", "accessToken", "refreshToken", "idToken",
                "secret", "apiKey", "authentication",
            ],
            ignoreUnknown = true,
        )
        @field:JsonTypeInfo(property = "@delegateClass", use = JsonTypeId.CLASS, include = JsonTypeAs.EXTERNAL_PROPERTY)
        val delegate: UserDescriptor,
    ) : BackgroundTaskUserDescriptor, UserDescriptor by delegate

    /**
     * Serializable stand-in for the well-known users [UserDescriptor.SYSTEM] and [UserDescriptor.ANONYMOUS],
     * which are not serializable themselves. Delegates to the well-known user of the given [username].
     */
    @JsonIgnoreProperties("userId", "identity", "authenticated", "roles", "principal", "isAdmin")
    data class WellKnown(
        /** Username of the well-known user */
        @field:JsonProperty("username", required = true)
        override val username: String,
    ) : BackgroundTaskUserDescriptor, UserDescriptor by wellKnownUser(username)

    companion object {
        /**
         * Wrap the given [user] in a [Delegate], serializing it including its class name
         * (or in a [WellKnown] user for [UserDescriptor.SYSTEM] and [UserDescriptor.ANONYMOUS]).
         * Mind the serialization of credentials described at [Delegate].
         */
        fun delegateOf(user: UserDescriptor): BackgroundTaskUserDescriptor = when (user) {
            UserDescriptor.SYSTEM, UserDescriptor.ANONYMOUS -> WellKnown(user.username)
            else -> Delegate(delegate = user, delegateClassName = user.javaClass.name)
        }

        /**
         * Look up the given well-known user (either [UserDescriptor.SYSTEM] or [UserDescriptor.ANONYMOUS])
         * from the given username. Throw an Exception if the username is not, in fact, well-known.
         */
        private fun wellKnownUser(username: String): UserDescriptor = when (username) {
            UserDescriptor.SYSTEM_USERNAME -> UserDescriptor.SYSTEM
            UserDescriptor.ANONYMOUS_USERNAME -> UserDescriptor.ANONYMOUS
            else -> throw IllegalArgumentException("Unknown well-known user: $username")
        }
    }
}

/**
 * Specialized [Converter] for converting a specific type of [UserDescriptor] into a serializable [BackgroundTaskUserDescriptor].
 * Register this converter with the application's `ConversionService` to enable application-level conversion of user
 * descriptors for background tasks, e.g. by declaring it as a bean in a Spring Web MVC application, which adds
 * `Converter` beans to its `mvcConversionService`.
 */
interface BackgroundTaskUserConverter<U : UserDescriptor> : Converter<U, BackgroundTaskUserDescriptor>
