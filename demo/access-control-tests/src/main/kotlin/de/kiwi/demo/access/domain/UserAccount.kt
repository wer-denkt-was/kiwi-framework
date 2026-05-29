package de.kiwi.demo.access.domain

import de.kiwi.api.domain.*
import jakarta.persistence.*

class UserAccountId(value: Long) : EntityId(value)

@Entity
@Table(name = "user_account")
class UserAccount(
    id: UserAccountId,

    @Column(name = "email", nullable = false, length = 255)
    val email: String,

    @Column(name = "username", nullable = false, columnDefinition = "text")
    val username: String,

    @Column(name = "full_name", nullable = true, columnDefinition = "text")
    val fullName: String?,

    @Column(name = "external_identity", unique = true, nullable = true, length = 255)
    val externalIdentity: String?,
) : BaseEntity<UserAccountId>(id) {

    @Basic
    @Convert(converter = UserRolesConverter::class)
    @Column(name = "user_roles", nullable = false, length = 255)
    val userRoles: UserAccountRoles = UserAccountRoles.empty()

    @ManyToMany
    @JoinTable(
        name = "authorized_electricity_contract",
        joinColumns = [JoinColumn(name = "user_account_id", referencedColumnName = "id")],
        inverseJoinColumns = [JoinColumn(name = "electricity_contract_id", referencedColumnName = "id")],
    )
    val accessToElectricityContracts: MutableSet<ElectricityContract> = HashSet()

    fun hasAccessToElectricityContract(contract: ElectricityContract) = hasAccessToElectricityContract(contract.id)
    fun hasAccessToElectricityContract(contractId: ElectricityContractId) =
        accessToElectricityContracts.any { it.id == contractId }

    fun modifyAccessToElectricityContract(contract: ElectricityContract, granted: Boolean) = DomainResult { record ->
        accessToElectricityContracts.addOrRemove(contract, present = granted) {
            userRoles.modifyRoleGranted(UserAccountRoles.ELECTRICITY_CONTRACT, granted = it.isNotEmpty())
        }
    }

    @ManyToMany
    @JoinTable(
        name = "authorized_solar_installer",
        joinColumns = [JoinColumn(name = "user_account_id", referencedColumnName = "id")],
        inverseJoinColumns = [JoinColumn(name = "solar_installer_id", referencedColumnName = "id")]
    )
    val accessToSolarInstallers: MutableSet<SolarInstaller> = HashSet()
    fun hasAccessToSolarInstaller(installer: SolarInstaller) = hasAccessToSolarInstaller(installerId = installer.id)
    fun hasAccessToSolarInstaller(installerId: SolarInstallerId) = accessToSolarInstallers.any { it.id == installerId }
    fun requireAccessToInstaller(installerId: SolarInstallerId) = accessToSolarInstallers.find { it.id == installerId }
        ?: throw AccountAccessDenied("SolarInstaller", installerId)

    fun modifyAccessToSolarInstaller(installer: SolarInstaller, granted: Boolean) = DomainResult { record ->
        accessToSolarInstallers.addOrRemove(installer, present = granted) {
            userRoles.modifyRoleGranted(UserAccountRoles.SOLAR_INSTALLER, granted = it.isNotEmpty())
        }
    }

    @ManyToMany
    @JoinTable(
        name = "authorized_solar_operator",
        joinColumns = [JoinColumn(name = "user_account_id", referencedColumnName = "id")],
        inverseJoinColumns = [JoinColumn(name = "solar_operator_id", referencedColumnName = "id")],
    )
    val accessToSolarOperators: MutableSet<SolarOperator> = HashSet()
    fun hasAccessToSolarOperator(operator: SolarOperator) = hasAccessToSolarOperator(operatorId = operator.id)
    fun hasAccessToSolarOperator(operatorId: SolarOperatorId) = accessToSolarOperators.any { it.id == operatorId }
    fun requireAccessToOperator(operatorId: SolarOperatorId) = accessToSolarOperators.find { it.id == operatorId }
        ?: throw AccountAccessDenied("SolarOperator", operatorId)

    fun modifyAccessToSolarOperator(operator: SolarOperator, granted: Boolean) = DomainResult { record ->
        accessToSolarOperators.addOrRemove(operator, present = granted) {
            userRoles.modifyRoleGranted(UserAccountRoles.SOLAR_OPERATOR, granted = it.isNotEmpty())
        }
    }

    fun modifyAdminRole(granted: Boolean) = DomainResult { record ->
        val modified = userRoles.modifyRoleGranted(UserAccountRoles.ADMIN, granted = granted)
    }

    fun hasRole(requiredRole: String): Boolean = requiredRole in userRoles

    val isAdmin get() = hasRole(UserAccountRoles.ADMIN)

    fun isSolarOperatorOfUnit(unit: LocationUnit): Boolean {
        val operator = unit.solarSystem.operator ?: return false
        return hasAccessToSolarOperator(operator)
    }

    fun isElectricityCustomerInSystem(solarSystemId: SolarSystemId): Boolean {
        return accessToElectricityContracts.any { it.solarSystem.id == solarSystemId }
    }

    companion object {
        fun create(
            email: String,
            username: String,
            fullName: String?,
            externalIdentity: String?,
        ) = UserAccount(
            id = EntityId.generate<UserAccountId>(),
            email = email,
            username = username,
            fullName = fullName,
            externalIdentity = externalIdentity,
        )
    }

    data class AccountAccessDenied(val type: String, val id: EntityId) : DomainException(
        status = Status.Forbidden,
        code = "user-account-access-denied",
        args = mapOf("type" to type, "id" to id.toString()),
    )
}

/**
 * Convenience function to check if a UserDescriptor can be matched to a given UserAccount entity
 */
fun UserDescriptor.matchesAccount(account: UserAccount?): Boolean {
    // Match UserDescriptor against UserAccount by ID or external identity
    return when {
        account == null -> false
        account.externalIdentity == null -> account.id.value == userId
        else -> account.externalIdentity == identity
    }
}
