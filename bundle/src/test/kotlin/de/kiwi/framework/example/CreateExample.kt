package de.kiwi.framework.example

import de.kiwi.api.application.generic.GenericCreateHandler
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.generic.GenericCreate
import org.springframework.stereotype.Component

class CreateExample : GenericCreate<ExampleEntity>() {
    override val detailsProvider = ExampleFieldsets.default
    val name by schema(ExampleSchema.name)
    val optional by schema(ExampleSchema.optional)
    override fun preAuthorize(user: UserDescriptor) {} //TODO
}

@Component
class CreateExampleHandler : GenericCreateHandler<CreateExample, ExampleEntity>() {
    override fun createEntity(create: CreateExample, user: UserDescriptor): DomainResult<ExampleEntity> {
        return ExampleEntity.create(name = create.name, optional = create.optional)
    }
}
