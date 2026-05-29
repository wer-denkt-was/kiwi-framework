package de.kiwi.framework.example

import de.kiwi.api.application.generic.GenericUpdateHandler
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.generic.GenericUpdate
import org.springframework.stereotype.Component

class UpdateExample : GenericUpdate<ExampleEntity, EntityId>() {
    override val entityFieldset = ExampleFieldsets.default
    override fun preAuthorize(user: UserDescriptor) {} //TODO
}

@Component
class UpdateExampleHandler : GenericUpdateHandler<UpdateExample, ExampleEntity>() {
    override fun updateEntity(update: UpdateBody, entity: ExampleEntity, user: UserDescriptor): DomainResult<*> {
        update.patchField(ExampleSchema.name)
        update.patchField(ExampleSchema.optional)
        return DomainResult.empty
    }
}
