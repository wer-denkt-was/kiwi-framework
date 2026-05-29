package de.kiwi.api.util

import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

/**
 * Create a [org.slf4j.Logger] for the given class.
 * Use like `private val log = slf4j(MyService::class)`.
 */
fun slf4j(`class`: KClass<*>) = LoggerFactory.getLogger(`class`.java)!!

/**
 * Create a [org.slf4j.Logger] for the given type.
 * Use like `private val log = slf4j<MyService>()`.
 */
inline fun <reified T> slf4j() = slf4j(T::class)
