package de.kiwi.demo.access.domain

import de.kiwi.api.domain.*
import jakarta.persistence.*

@Embeddable
class SolarInstallerId(value: Long) : EntityId(value)

@Entity
@Table(name = "solar_installer")
class SolarInstaller(
    id: SolarInstallerId,

    @Column(name = "company_name", nullable = false, length = 1024)
    val companyName: String,

    @Column(name = "address", nullable = false, columnDefinition = "text")
    val address: String,
) : BaseEntity<SolarInstallerId>(id) {

    @ManyToMany(mappedBy = "accessToSolarInstallers")
    val userAccounts: MutableSet<UserAccount> = HashSet()

    @OneToMany(mappedBy = "installer")
    val installedSystems: MutableSet<SolarSystem> = HashSet()

    companion object {

        val validCompanyName = SolarInstaller::companyName.validateColumn() and Validation.notBlank
        val validAddress = SolarInstaller::address.validateColumn() and Validation.notBlank

        fun create(
            companyName: String,
            address: String,
        ) = SolarInstaller(
            id = EntityId.generate<SolarInstallerId>(),
            companyName = validCompanyName(companyName),
            address = validAddress(address),
        )
    }
}

