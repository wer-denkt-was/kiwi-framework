package de.kiwi.demo.access.domain

import de.kiwi.api.domain.BaseEntity
import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.validateColumn
import jakarta.persistence.*

enum class MonitorType { SolarSystem, LocationUnit }

@Embeddable
class ElectricityMonitorId(value: Long) : EntityId(value)

@Entity
@Table(name = "electricity_monitor")
class ElectricityMonitor(
    id: ElectricityMonitorId,

    @Column(name = "monitor_number", length = 255)
    var monitorNumber: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "monitor_type")
    val monitorType: MonitorType,

    @ManyToOne(optional = false)
    @JoinColumn(name = "solar_system_id", nullable = false)
    val solarSystem: SolarSystem,
) : BaseEntity<ElectricityMonitorId>(id) {
    companion object {

        val validMonitorNumber = ElectricityMonitor::monitorNumber.validateColumn()

        fun create(
            monitorNumber: String,
            solarSystem: SolarSystem,
            monitorType: MonitorType,
        ) = ElectricityMonitor(
            id = EntityId.generate<ElectricityMonitorId>(),
            monitorNumber = validMonitorNumber(monitorNumber),
            monitorType = monitorType,
            solarSystem = solarSystem,
        )
    }
}
