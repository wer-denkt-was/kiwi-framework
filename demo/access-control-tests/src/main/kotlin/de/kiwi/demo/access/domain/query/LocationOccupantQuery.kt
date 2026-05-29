package de.kiwi.demo.access.domain.query

import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.generic.GenericEntityQuery
import de.kiwi.demo.access.domain.LocationOccupant
import de.kiwi.demo.access.domain.accessControl.readLocationOccupant

class LocationOccupantQuery : GenericEntityQuery<LocationOccupant>() {
    override val accessControl = readLocationOccupant
    override val accessControlAllowAdmin = true

    override fun filterById(id: EntityId) = baseEntityFilter<LocationOccupant>(id)
    override fun preAuthorize(user: UserDescriptor) = Unit
}
