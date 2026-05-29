package de.kiwi.demo.access.application

import de.kiwi.demo.access.domain.SolarOperator
import de.kiwi.demo.access.domain.SolarOperatorId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.stereotype.Repository

@Repository
interface SolarOperatorRepository :
    JpaRepository<SolarOperator, SolarOperatorId>, JpaSpecificationExecutor<SolarOperator>
