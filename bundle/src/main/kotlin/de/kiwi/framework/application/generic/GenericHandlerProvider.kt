package de.kiwi.framework.application.generic

import tools.jackson.databind.JsonNode
import de.kiwi.api.domain.Command
import de.kiwi.api.domain.EntityId
import de.kiwi.api.generic.GenericEntityQuery

/**
 * Internal service interface for providing generic create, update, and delete [Commands][Command], and
 * entity [Queries][GenericEntityQuery].
 */
interface GenericHandlerProvider {
    val createClasses: Map<Class<*>, Class<out Command<*>>>
    val updateClasses: Map<Class<*>, Class<out Command<*>>>
    val deleteClasses: Map<Class<*>, Class<out Command<*>>>
    val queryClasses: Map<Class<*>, Class<out GenericEntityQuery<*>>>

    fun createCommand(entityType: Class<*>, body: JsonNode): Command<*>
    fun updateCommand(entityType: Class<*>, id: EntityId, body: JsonNode): Command<*>
    fun deleteCommand(entityType: Class<*>, id: EntityId): Command<*>
    fun <E : Any> entityQuery(entityType: Class<E>): GenericEntityQuery<E>
}
