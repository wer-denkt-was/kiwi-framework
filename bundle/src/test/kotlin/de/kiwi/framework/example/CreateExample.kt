package de.kiwi.framework.example

import de.kiwi.api.application.generic.GenericCreateHandler
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.generic.GenericCreate
import org.springframework.stereotype.Component

class CreateExample : GenericCreate<ExampleEntity>() {
    override val entityFieldset = ExampleFieldsets.default
    override fun entityId(entity: ExampleEntity) = entity.id
    override fun preAuthorize(user: UserDescriptor) {} //TODO
}

@Component
class CreateExampleHandler : GenericCreateHandler<CreateExample, ExampleEntity>() {
    override fun createEntity(create: CreateBody, user: UserDescriptor): DomainResult<ExampleEntity> {
        return ExampleEntity.create(
            name = create.require(ExampleSchema.name),
            optional = create[ExampleSchema.optional],
        )
    }
}
