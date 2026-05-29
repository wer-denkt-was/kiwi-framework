package de.kiwi.demo.access.domain.schema

import de.kiwi.api.schema.EntitySchema
import de.kiwi.api.schema.FieldsetsAvailable
import de.kiwi.demo.access.domain.SolarInstaller
import de.kiwi.demo.access.domain.SolarInstaller_

object SolarInstallerSchema : EntitySchema<SolarInstaller>() {
    val id by entityId()
    val companyName by column<String>(SolarInstaller::companyName)
    val address by column<String>(SolarInstaller::address)
    val installedSystems by toMany(SolarInstaller::installedSystems, SolarSystemSchema)
}

object SolarInstallerFieldsets : FieldsetsAvailable<SolarInstaller>(SolarInstallerSchema) {
    override val default by defineFieldset {
        includeAllColumns()
    }

    val short by defineFieldset {
        include(SolarInstallerSchema.id)
        include(SolarInstallerSchema.companyName)
    }

    val details by defineFieldset {
        extendsFieldset(default)
        includeToMany(SolarInstallerSchema.installedSystems, SolarSystemFieldsets.short)
    }
}
