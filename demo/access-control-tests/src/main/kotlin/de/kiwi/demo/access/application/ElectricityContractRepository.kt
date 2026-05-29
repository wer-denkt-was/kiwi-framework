package de.kiwi.demo.access.application

import de.kiwi.demo.access.domain.ElectricityContract
import de.kiwi.demo.access.domain.ElectricityContractId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.stereotype.Repository

@Repository
interface ElectricityContractRepository :
    JpaRepository<ElectricityContract, ElectricityContractId>, JpaSpecificationExecutor<ElectricityContract>
