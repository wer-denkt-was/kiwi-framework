package de.kiwi.demo.access.application

import de.kiwi.demo.access.domain.SolarSystem
import de.kiwi.demo.access.domain.SolarSystemId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.stereotype.Repository

@Repository
interface SolarSystemRepository : JpaRepository<SolarSystem, SolarSystemId>, JpaSpecificationExecutor<SolarSystem>
