package de.kiwi.testing.duplicates

import de.kiwi.api.schema.EntitySchema
import de.kiwi.framework.infrastructure.generic.Publisher

// Two schemas for the same entity type, see SpringQuerySchemaProviderTest (outside of de.kiwi.framework, which other
// tests scan for schemas)

object FirstPublisherSchema : EntitySchema<Publisher>()

object SecondPublisherSchema : EntitySchema<Publisher>()
