package de.kiwi.demo.access.domain

import de.kiwi.api.domain.BaseEntity
import de.kiwi.api.domain.EntityId
import jakarta.persistence.*
import java.time.LocalDate

@Embeddable
class ElectricityContractId(value: Long) : EntityId(value)

@Entity
@Table(name = "electricity_contract")
class ElectricityContract(
    id: ElectricityContractId,
    occupant: LocationOccupant,

    @Column(name = "contract_start_date")
    val contractStart: LocalDate,

    @Column(name = "contract_signed_date")
    val contractSigned: LocalDate,

    @Column(name = "terms_accepted_date")
    val termsAccepted: LocalDate,
) : BaseEntity<ElectricityContractId>(id) {

    @OneToOne(optional = false)
    @JoinColumn(name = "occupant_id", nullable = false)
    val occupant: LocationOccupant = occupant

    @ManyToOne(optional = false)
    @JoinColumn(name = "solar_system_id", nullable = false)
    val solarSystem: SolarSystem = occupant.locationUnit.solarSystem

    @Column(name = "contract_end_date")
    val contractEnd: LocalDate? = null

    companion object {
        fun create(
            occupant: LocationOccupant,
            contractStart: LocalDate,
            contractSigned: LocalDate,
            termsAccepted: LocalDate,
        ) = ElectricityContract(
            id = EntityId.generate<ElectricityContractId>(),
            occupant = occupant,
            contractStart = contractStart,
            contractSigned = contractSigned,
            termsAccepted = termsAccepted,
        )
    }
}
