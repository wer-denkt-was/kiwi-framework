package de.kiwi.api.generic

import de.kiwi.api.domain.EntityId
import kotlin.reflect.KClass

/**
 * Annotation for [Command][de.kiwi.api.domain.Command] classes that should be bound to the
 * generic entity controller `HTTP POST` create method of the given entity type.
 *
 * __NOTE__: The annotated command class is deserialized from the JSON request body using Jackson.
 * @param value Entity type for create method
 */
@MustBeDocumented
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class CreateCommand(val value: KClass<*>)

/**
 * Annotation for [Command][de.kiwi.api.domain.Command] classes that should be bound to the
 * generic entity controller `HTTP PATCH` update method of the given entity type.
 *
 * __NOTE__: The annotated command class *must* provide a single-argument constructor accepting an [EntityId] of [idType].
 * @param value Entity type for which the annotated command provides an update method
 * @param idType EntityId type of the entity
 */
@MustBeDocumented
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class UpdateCommand(val value: KClass<*>, val idType: KClass<out EntityId>)

/**
 * Annotation for [Command][de.kiwi.api.domain.Command] classes that should be bound to the
 * generic entity controller `HTTP DELETE` update method of the given entity type.
 *
 * __NOTE__: The annotated command class *must* provide a single-argument constructor accepting an [EntityId] of [idType].
 * @param value Entity type for which the annotated command provides a delete method
 * @param idType EntityId type of the entity
 */
@MustBeDocumented
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class DeleteCommand(val value: KClass<*>, val idType: KClass<out EntityId>)
