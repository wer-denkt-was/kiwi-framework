package de.kiwi.framework.infrastructure.generic

import de.kiwi.api.application.generic.GenericCreateHandler
import de.kiwi.api.application.generic.GenericDeleteHandler
import de.kiwi.api.application.generic.GenericUpdateHandler
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.UserDescriptor
import org.springframework.stereotype.Component

@Component
class CreatePublisherHandler : GenericCreateHandler<CreatePublisher, Publisher>() {
    override fun createEntity(create: CreateBody, user: UserDescriptor) = DomainResult.of(
        Publisher(
            id = EntityId.generate<PublisherId>(),
            name = create.require(PublisherSchema.name),
            contact = create.require(PublisherSchema.contact),
        )
    )
}

@Component
class UpdatePublisherHandler : GenericUpdateHandler<UpdatePublisher, Publisher>() {
    override fun updateEntity(update: UpdateBody, entity: Publisher, user: UserDescriptor): DomainResult<*> {
        update.patchField(PublisherSchema.name)
        update.patchField(PublisherSchema.contact)
        return DomainResult.empty
    }
}

@Component
class DeletePublisherHandler : GenericDeleteHandler<DeletePublisher, Publisher>() {
    override fun beforeDeleteEntity(delete: DeletePublisher, entity: Publisher, user: UserDescriptor) =
        DomainResult.empty
}
