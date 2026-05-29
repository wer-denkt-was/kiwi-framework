package de.kiwi.demo.access.application

import de.kiwi.demo.access.domain.ElectricityMonitor
import de.kiwi.demo.access.domain.ElectricityMonitorId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.stereotype.Repository

@Repository
interface ElectricityMonitorRepository :
    JpaRepository<ElectricityMonitor, ElectricityMonitorId>, JpaSpecificationExecutor<ElectricityMonitor>
