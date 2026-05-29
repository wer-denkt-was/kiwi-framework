package de.kiwi.demo.access.application

import de.kiwi.demo.access.domain.LocationOccupant
import de.kiwi.demo.access.domain.LocationOccupantId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.stereotype.Repository

@Repository
interface LocationOccupantRepository :
    JpaRepository<LocationOccupant, LocationOccupantId>, JpaSpecificationExecutor<LocationOccupant>
