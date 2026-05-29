package de.kiwi.demo.access.domain

import de.kiwi.api.domain.BaseEntity
import de.kiwi.api.domain.EntityId
import jakarta.persistence.*
import java.time.LocalDate

@Embeddable
class LocationOccupantId(value: Long) : EntityId(value)

@Entity
@Table(name = "location_occupant")
class LocationOccupant(
    id: LocationOccupantId,

    @Column(name = "move_in_date")
    val moveInDate: LocalDate,

    @Column(name = "name", nullable = true)
    var name: String?,

    @Column(name = "address", nullable = true)
    var address: String?,

    @Column(name = "email", nullable = true)
    var email: String?,

    @Column(name = "phone_number", nullable = true)
    var phoneNumber: String?,

    @ManyToOne(optional = false)
    @JoinColumn(name = "location_unit_id", nullable = false)
    val locationUnit: LocationUnit,
) : BaseEntity<LocationOccupantId>(id) {

    @Column(name = "move_out_date")
    var moveOutDate: LocalDate? = null

    @OneToOne(mappedBy = "occupant", optional = true)
    var electricityContract: ElectricityContract? = null

    companion object {
        fun create(
            locationUnit: LocationUnit,
            moveInDate: LocalDate,
            name: String?,
            address: String?,
            email: String?,
            phoneNumber: String?,
        ) = LocationOccupant(
            id = EntityId.generate<LocationOccupantId>(),
            moveInDate = moveInDate,
            name = name,
            address = address,
            email = email,
            phoneNumber = phoneNumber,
            locationUnit = locationUnit,
        )
    }
}
