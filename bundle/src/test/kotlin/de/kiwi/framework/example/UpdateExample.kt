package de.kiwi.framework.example

import de.kiwi.api.application.generic.GenericUpdateHandler
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.generic.GenericUpdate
import org.springframework.stereotype.Component

class UpdateExample : GenericUpdate<ExampleEntity, EntityId>() {
    override val detailsProvider = ExampleFieldsets.default
    val name by schema(ExampleSchema.name)
    val optional by schema(ExampleSchema.optional)
    override fun preAuthorize(user: UserDescriptor) {} //TODO
}

@Component
class UpdateExampleHandler : GenericUpdateHandler<UpdateExample, ExampleEntity>() {
    override fun updateEntity(update: UpdateExample, entity: ExampleEntity, user: UserDescriptor): DomainResult<*> {
        update.patchFields(entity, update::name, update::optional)
        return DomainResult.empty
    }
}
