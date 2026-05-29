package de.kiwi.demo.access.application

import de.kiwi.demo.access.domain.SolarInstaller
import de.kiwi.demo.access.domain.SolarInstallerId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.stereotype.Repository

@Repository
interface SolarInstallerRepository :
    JpaRepository<SolarInstaller, SolarInstallerId>, JpaSpecificationExecutor<SolarInstaller>
