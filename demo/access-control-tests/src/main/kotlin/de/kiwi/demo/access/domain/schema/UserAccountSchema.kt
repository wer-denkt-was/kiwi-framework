package de.kiwi.demo.access.domain.schema

import de.kiwi.api.schema.EntitySchema
import de.kiwi.api.schema.EntitySchemaField
import de.kiwi.api.schema.FieldsetsAvailable
import de.kiwi.api.schema.*
import de.kiwi.demo.access.TestUserAccountHolder
import de.kiwi.demo.access.domain.UserAccount
import de.kiwi.demo.access.domain.UserAccountRoles
import de.kiwi.demo.access.domain.UserAccount_
import de.kiwi.demo.access.domain.accessControl.selfOnlyFieldAccess

object UserAccountSchema : EntitySchema<UserAccount>() {
    val id by entityId()
    val email by column<String>(UserAccount::email)
    val username by column<String>(UserAccount::username)
    val fullName by column<String?>(UserAccount::fullName)
    val roles by column<UserAccountRoles, _>(UserAccount::userRoles, type = UserRolesType)
    val externalIdentity by column<String?>(UserAccount::externalIdentity)

    val accessToSolarOperators by toMany(UserAccount::accessToSolarOperators, SolarOperatorSchema)
    val accessToSolarInstallers by toMany(UserAccount::accessToSolarInstallers, SolarInstallerSchema)
    val accessToElectricityContracts by toMany(UserAccount::accessToElectricityContracts, ElectricityContractSchema)

    override fun fieldAccessControl(field: EntitySchemaField<UserAccount>) = when (field) {
        externalIdentity -> TestUserAccountHolder.fieldAdminOnly()
        accessToSolarOperators, accessToSolarInstallers, accessToElectricityContracts -> selfOnlyFieldAccess
        else -> null
    }
}

object UserAccountFieldsets : FieldsetsAvailable<UserAccount>(UserAccountSchema) {
    override val default by defineFieldset {
        includeAllColumns()
        exclude(UserAccountSchema.externalIdentity)
    }

    val details by defineFieldset {
        extendsFieldset(default)
        include(UserAccountSchema.externalIdentity)
        includeToMany(UserAccountSchema.accessToSolarInstallers, SolarInstallerFieldsets.short)
        includeToMany(UserAccountSchema.accessToSolarOperators, SolarOperatorFieldsets.short)
        includeToMany(UserAccountSchema.accessToElectricityContracts, ElectricityContractFieldsets.short)
    }
}
