package de.kiwi.demo.access.domain

import de.kiwi.api.domain.BaseEntity
import de.kiwi.api.domain.EntityId
import jakarta.persistence.*

@Embeddable
class SolarOperatorId(value: Long) : EntityId(value)

@Entity
@Table(name = "solar_operator")
class SolarOperator(
    id: SolarOperatorId,

    @Column(name = "company_name", nullable = false, length = 1024)
    val companyName: String,

    @Column(name = "address", nullable = false, columnDefinition = "text")
    val address: String,
) : BaseEntity<SolarOperatorId>(id) {

    @ManyToMany(mappedBy = "accessToSolarOperators")
    val userAccounts: MutableSet<UserAccount> = HashSet()

    @OneToMany(mappedBy = "operator")
    val operatedSystems: MutableSet<SolarSystem> = HashSet()

    companion object {
        fun create(
            companyName: String,
            address: String,
        ) = SolarOperator(
            id = EntityId.generate<SolarOperatorId>(),
            companyName = companyName,
            address = address,
        )
    }
}
