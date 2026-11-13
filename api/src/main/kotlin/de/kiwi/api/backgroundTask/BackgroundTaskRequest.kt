package de.kiwi.api.backgroundTask

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.io.Serializable

/**
 * Abstract base class for requests launching background tasks, executed by a [BackgroundTaskHandler].
 * Each request belongs to a specific task [category], which can be specified by the request implementation.
 * Subclasses are polymorphically deserializable by Jackson, with the class being included in JSON in the `@class` property.
 *
 * Note: Request classes *should* be `data class`, and *must* be serializable/deserializable by Jackson.
 * The request is stored as JSON with the task, and sent to the worker executing the task, so the request class must be
 * available on the classpath of the workers.
 * @see BackgroundTaskHandler
 * @see BackgroundTaskDescriptor
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(property = "@class", use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY)
abstract class BackgroundTaskRequest : Serializable {

    /**
     * Category of the task, [DEFAULT_CATEGORY] by default. The category selects the queue the task is enqueued in,
     * and thereby the workers that may execute it. The category is not serialized, so it must be derived from the
     * request class or its serialized properties.
     */
    @get:JsonIgnore
    open val category: String get() = DEFAULT_CATEGORY

    companion object {
        /** Name of the default task category */
        const val DEFAULT_CATEGORY = "default"
    }
}
