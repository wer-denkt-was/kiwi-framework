package de.kiwi.framework.example

import de.kiwi.api.application.generic.GenericDeleteHandler
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.generic.GenericDelete
import org.springframework.stereotype.Component

class DeleteExample : GenericDelete<ExampleEntity, EntityId>() {
    override val detailsProvider = ExampleFieldsets.default
    override fun preAuthorize(user: UserDescriptor) {} //TODO
}

@Component
class DeleteExampleHandler : GenericDeleteHandler<DeleteExample, ExampleEntity>() {
    override fun beforeDeleteEntity(delete: DeleteExample, entity: ExampleEntity, user: UserDescriptor) =
        DomainResult.empty
}
