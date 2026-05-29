package de.kiwi.api.util

import org.springframework.core.GenericTypeResolver
import kotlin.reflect.KClass

/**
 * Resolves the class of a specific generic type argument for a given supertype [S] at the specified [parameterIndex].
 *
 * @param parameterIndex The index of the generic parameter to resolve.
 * @return The [Class] of the resolved generic type argument.
 * @throws AssertionError if the generic type argument cannot be resolved.
 */
inline fun <reified S> Class<*>.lookupSupertypeArgument(parameterIndex: Int): Class<*> =
    lookupSupertypeArgument(S::class, parameterIndex)

/**
 * Resolves the class of a specific generic type argument for the given [superType] at the specified [parameterIndex].
 *
 * @param superType The [KClass] of the supertype whose generic argument is to be resolved.
 * @param parameterIndex The index of the generic parameter to resolve.
 * @return The [Class] of the resolved generic type argument.
 * @throws AssertionError if the generic type argument cannot be resolved.
 */
fun Class<*>.lookupSupertypeArgument(superType: KClass<*>, parameterIndex: Int): Class<*> =
    lookupSupertypeArguments(superType)[parameterIndex]

/**
 * Resolves all generic type arguments for a given supertype [S].
 *
 * @return An array of [Class] objects representing the resolved generic type arguments.
 * @throws AssertionError if the generic type arguments cannot be resolved.
 */
inline fun <reified S> Class<*>.lookupSupertypeArguments(): Array<Class<*>> =
    lookupSupertypeArguments(S::class)

/**
 * Resolves all generic type arguments for the given [superType].
 *
 * @param superType The [KClass] of the supertype whose generic arguments are to be resolved.
 * @return An array of [Class] objects representing the resolved generic type arguments.
 * @throws AssertionError if the generic type arguments cannot be resolved.
 */
fun Class<*>.lookupSupertypeArguments(superType: KClass<*>): Array<Class<*>> =
    GenericTypeResolver.resolveTypeArguments(this, superType.java)
        ?: throw AssertionError("Could not resolve generic superclass ${superType.qualifiedName} for class $name")
