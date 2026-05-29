package de.kiwi.demo.access.domain

import de.kiwi.api.domain.BaseEntity
import de.kiwi.api.domain.EntityId
import jakarta.persistence.*

@Embeddable
class LocationUnitId(value: Long) : EntityId(value)

@Entity
@Table(
    name = "location_unit",
    uniqueConstraints = [
        UniqueConstraint(
            name = "unique_location_unit_solar_system_identifier",
            columnNames = ["solar_system_id", "unit_number"],
        )
    ]
)
class LocationUnit(
    id: LocationUnitId,

    @ManyToOne(optional = false)
    @JoinColumn(name = "solar_system_id", nullable = false)
    val solarSystem: SolarSystem,

    @Column(name = "unit_number", nullable = false, length = 255)
    var unitNumber: String,

    @OneToOne
    @JoinColumn(name = "monitor_id", nullable = false)
    val monitor: ElectricityMonitor,
) : BaseEntity<LocationUnitId>(id) {

    @OneToMany(mappedBy = "locationUnit", orphanRemoval = true)
    val occupants: MutableSet<LocationOccupant> = HashSet()

    companion object {
        fun create(
            unitNumber: String,
            solarSystem: SolarSystem,
            monitor: ElectricityMonitor,
        ): LocationUnit {

            require(monitor.monitorType == MonitorType.LocationUnit) { "Wrong Monitor Type" }

            return LocationUnit(
                id = EntityId.generate<LocationUnitId>(),
                unitNumber = unitNumber,
                solarSystem = solarSystem,
                monitor = monitor,
            )
        }
    }
}
