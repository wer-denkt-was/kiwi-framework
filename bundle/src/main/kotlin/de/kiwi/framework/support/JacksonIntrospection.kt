package de.kiwi.framework.support

import tools.jackson.databind.BeanDescription
import tools.jackson.databind.JavaType
import tools.jackson.databind.ObjectMapper

// Jackson 3 removed DeserializationConfig.introspect*(), bean introspection is performed using a DeserializationContext

/** Introspect the given type for deserialization, i.e. properties with setters or fields */
internal fun ObjectMapper.introspectForDeserialization(type: JavaType): BeanDescription =
    _deserializationContext().introspectBeanDescription(type)

/** Introspect the given type for creation, i.e. properties set using creator (constructor) parameters */
internal fun ObjectMapper.introspectForCreation(type: JavaType): BeanDescription =
    _deserializationContext().introspectBeanDescriptionForCreation(type)
