package de.kiwi.demo.access.application

import de.kiwi.demo.access.domain.LocationUnit
import de.kiwi.demo.access.domain.LocationUnitId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.stereotype.Repository

@Repository
interface LocationUnitRepository :
    JpaRepository<LocationUnit, LocationUnitId>, JpaSpecificationExecutor<LocationUnit>
