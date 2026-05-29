package de.kiwi.api.generic

import de.kiwi.api.domain.Validation
import de.kiwi.api.util.slf4j
import jakarta.persistence.metamodel.SingularAttribute
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty0
import kotlin.reflect.KProperty1
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.instanceParameter
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.jvmErasure
import kotlin.reflect.typeOf

private val log = slf4j<ColumnAutoValidation>()
private val validationType = typeOf<Validation<*>>()

/**
 * Helper object for looking up [Validation] properties associated with entity fields,
 * declared within companion objects of [@Entity][jakarta.persistence.Entity] classes.
 *
 * ## Auto-validation Lookup Algorithm
 *
 * 1. When a [SingularAttribute] is provided, the algorithm locates the companion object of the attribute's entity class.
 * 2. It scans all non-nullable properties in the companion object that are of type [Validation].
 * 3. For each [Validation], it checks if it is associated with a field using [Validation.associatedProperty].
 * 4. If a [Validation] is found whose associated field name matches the attribute's name, it is returned and cached.
 * 5. If no match is found, no auto-validation is applied for that attribute.
 *
 * This enables automatic application of field-level validation logic during entity creation or update,
 * without requiring explicit wiring for each field.
 *
 * ## Example
 *
 * ```kotlin
 * @Entity
 * class MyEntity(
 *   id: MyEntityId,
 *   @Column val name: String,
 *   @Column val age: Int,
 * ) : BaseEntity<MyEntityId>(id) {
 *   companion object {
 *     val validName = MyEntity::name.validateColumn() and Validation.notBlank
 *     val validAge = MyEntity::age.validateColumn() and Validation.assert<Int>("invalid-age") { it >= 0 }
 *   }
 * }
 * ```
 *
 * In this example, `validName` and `validAge` are eligible for auto-validation and will be
 * automatically applied to the corresponding fields during entity operations.
 */
internal object ColumnAutoValidation {
    private val attributeValidationCache = ConcurrentHashMap<KProperty1<*, *>, Validation<*>>()
    private val companionValidationsCache = ConcurrentHashMap<Any, Map<String, Validation<*>>>()

    private val noValidation = Validation<Nothing> { throw AssertionError("should never be evaluated") }

    fun <T : Any> lookupAutoValidation(attribute: KProperty1<*, T?>): Validation<T>? {
        val validation = attributeValidationCache.computeIfAbsent(attribute) {
            findAssociatedValidation(it)
        }

        @Suppress("UNCHECKED_CAST")
        return if (validation === noValidation) null
        else validation as Validation<T>
    }

    // Find a Validation associated with the given SingularAttribute
    private fun findAssociatedValidation(attribute: KProperty1<*, *>): Validation<*> {
        val entityKClass = attribute.instanceParameter?.type?.jvmErasure
        val companion = entityKClass?.companionObjectInstance ?: return noValidation
        val declaredValidations = companionValidationsCache.computeIfAbsent(companion) {
            findDeclaredValidationsInCompanion(it)
        }

        val found = declaredValidations[attribute.name] ?: return noValidation
        log.debug("Found associated Validation for attribute ${entityKClass.simpleName}::${attribute.name}")
        return found
    }

    // Find companion object properties of type Validation<*> associated with entity fields
    private fun findDeclaredValidationsInCompanion(companion: Any): Map<String, Validation<*>> {
        fun companionPropertyValue(prop: KProperty<*>): Validation<*>? {
            @Suppress("UNCHECKED_CAST")
            return ((prop as? KProperty0<*>)?.get()
                ?: (prop as? KProperty1<Any, *>)?.get(companion))
                ?.let { it as? Validation<*> }
        }

        return companion::class
            .declaredMemberProperties
            .asSequence()
            .filter { !it.returnType.isMarkedNullable }
            .onEach { it.javaField?.trySetAccessible() }
            .onEach { it.javaGetter?.trySetAccessible() }
            .filter { it.returnType.isSubtypeOf(validationType) }
            .mapNotNull { validationProperty ->
                companionPropertyValue(validationProperty)?.let { validation ->
                    validation.associatedProperty()?.let { associatedProperty ->
                        associatedProperty.name to validation
                    }
                }
            }
            .toMap()
    }
}
