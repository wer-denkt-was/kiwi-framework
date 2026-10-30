package de.kiwi.testing.misconfigured

import de.kiwi.api.application.generic.GenericUpdateHandler
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.generic.GenericUpdate
import de.kiwi.framework.infrastructure.generic.Author
import de.kiwi.framework.infrastructure.generic.AuthorFieldsets
import de.kiwi.framework.infrastructure.generic.AuthorId
import de.kiwi.framework.infrastructure.generic.AuthorSchema
import org.springframework.stereotype.Component

/** Update command whose handler patches the read-only primary key column, see `GenericEntityControllerTest` */
class MisconfiguredUpdateAuthor : GenericUpdate<Author, AuthorId>() {
    override val detailsProvider = AuthorFieldsets.default
    val id by schema(AuthorSchema.id)
    val fullName by schema(AuthorSchema.fullName)
    override fun preAuthorize(user: UserDescriptor) {}
}

@Component
class MisconfiguredUpdateAuthorHandler : GenericUpdateHandler<MisconfiguredUpdateAuthor, Author>() {
    override fun updateEntity(update: MisconfiguredUpdateAuthor, entity: Author, user: UserDescriptor): DomainResult<*> {
        update.patchFields(entity, update::fullName, update::id)
        return DomainResult.empty
    }
}
