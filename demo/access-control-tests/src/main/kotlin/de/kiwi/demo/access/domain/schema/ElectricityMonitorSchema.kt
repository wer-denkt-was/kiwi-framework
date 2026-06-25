package de.kiwi.demo.access.domain.schema

import de.kiwi.api.schema.ColumnType
import de.kiwi.api.schema.EntitySchema
import de.kiwi.api.schema.FieldsetsAvailable
import de.kiwi.demo.access.domain.ElectricityMonitor
import de.kiwi.demo.access.domain.ElectricityMonitor_
import de.kiwi.demo.access.domain.MonitorType

private val monitorTypeColumnType = ColumnType.enum<MonitorType>()

object ElectricityMonitorSchema : EntitySchema<ElectricityMonitor>() {
    val id by entityId()
    val monitorNumber by column(ElectricityMonitor::monitorNumber)
    val monitorType by column<MonitorType, _>(ElectricityMonitor::monitorType, monitorTypeColumnType)
    val solarSystem by toOne(ElectricityMonitor::solarSystem, SolarSystemSchema)
}

object ElectricityMonitorFieldsets : FieldsetsAvailable<ElectricityMonitor>(ElectricityMonitorSchema) {
    override val default by defineFieldset {
        includeAllColumns()
    }

    val short by defineFieldset {
        include(ElectricityMonitorSchema.id)
        include(ElectricityMonitorSchema.monitorNumber)
        include(ElectricityMonitorSchema.monitorType)
    }

    val details by defineFieldset {
        extendsFieldset(default)
    }
}
