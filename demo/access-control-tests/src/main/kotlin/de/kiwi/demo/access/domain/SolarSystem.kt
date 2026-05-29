package de.kiwi.demo.access.domain

import de.kiwi.api.domain.BaseEntity
import de.kiwi.api.domain.EntityId
import jakarta.persistence.*
import java.time.LocalDate

enum class InstallStatus { Preparation, Active }
enum class ImsStatus { Preparation, Requested, Planned, Active }

@Embeddable
class SolarSystemId(value: Long) : EntityId(value)

@Entity
@Table(name = "solar_system")
class SolarSystem(
    id: SolarSystemId,

    @Column(name = "name", nullable = false, length = 1024)
    val name: String,

    @Column(name = "model", nullable = false, columnDefinition = "text")
    val model: String,

    @Column(name = "invoice_address", nullable = false, columnDefinition = "text")
    val invoiceAddress: String,

    @Column(name = "electricity_price", nullable = false, columnDefinition = "long")
    var electricityPrice: Long, //TODO( electricity price to database converter )

    @Enumerated(EnumType.STRING)
    @Column(name = "installation_status", nullable = false, columnDefinition = "enum('Preparation', 'Active')")
    var installStatus: InstallStatus,

    @Enumerated(EnumType.STRING)
    @Column(
        name = "ims_status",
        nullable = false,
        columnDefinition = "enum('Preparation','Requested','Planned','Active')"
    )
    var imsStatus: ImsStatus,

    @Column(name = "ims_requested_on", nullable = true)
    var imsRequestedOn: LocalDate?,

    @Column(name = "ims_install_date", nullable = true)
    var imsInstallDate: LocalDate?,

    @OneToOne(optional = true, orphanRemoval = true)
    @JoinColumn(name = "monitor_id", nullable = true)
    var feedInMonitor: ElectricityMonitor?,

    @ManyToOne(optional = true)
    @JoinColumn(name = "installer_id", nullable = true)
    var installer: SolarInstaller?,

    @ManyToOne(optional = true)
    @JoinColumn(name = "operator_id", nullable = true)
    var operator: SolarOperator?,
) : BaseEntity<SolarSystemId>(id) {

    @OneToMany(mappedBy = "solarSystem")
    val units: MutableSet<LocationUnit> = HashSet()

    @OneToMany(mappedBy = "solarSystem")
    val monitors: MutableSet<ElectricityMonitor> = HashSet()

    @OneToMany(mappedBy = "solarSystem")
    val electricityContracts: MutableSet<ElectricityContract> = HashSet()

    companion object {
        fun create(
            name: String,
            model: String,
            electricityPrice: Long,
            invoiceAddress: String,
            installer: SolarInstaller?,
            operator: SolarOperator?,
        ) = SolarSystem(
            id = EntityId.generate<SolarSystemId>(),
            name = name,
            model = model,
            invoiceAddress = invoiceAddress,
            installStatus = InstallStatus.Preparation,
            imsStatus = ImsStatus.Preparation,
            imsRequestedOn = null,
            imsInstallDate = null,
            electricityPrice = electricityPrice,
            installer = installer,
            operator = operator,
            feedInMonitor = null,
        )
    }


    fun hasInstallerOrOperatorAccess(account: UserAccount): Boolean =
        hasOperatorAccess(account) || hasInstallerAccess(account)

    fun hasOperatorAccess(account: UserAccount): Boolean {
        val subject = operator ?: return false
        return account.hasAccessToSolarOperator(subject)
    }

    fun hasInstallerAccess(account: UserAccount): Boolean {
        val subject = installer ?: return false
        return account.hasAccessToSolarInstaller(subject)
    }

    fun assignInstaller(newInstaller: SolarInstaller?) {
        installer = newInstaller
    }

    fun assignOperator(newOperator: SolarOperator?) {
        operator = newOperator
    }

    fun assignFeedInMonitor(newFeedInMonitor: ElectricityMonitor) {
        require(feedInMonitor == null) { "FeedInMonitor already assigned" }
        require(newFeedInMonitor.solarSystem.id == id) { "Wrong solar system" }
        require(newFeedInMonitor.monitorType == MonitorType.SolarSystem) { "Wrong monitor type" }
        feedInMonitor = newFeedInMonitor
    }

    fun updateElectricityPrice(newPrice: Long) {
        require(newPrice >= 0L) { "illegalPrice" }
        this.electricityPrice = newPrice
    }

    fun changeImsStatus(newImsState: ImsStatus, installDate: LocalDate?) {
        when (newImsState) {
            ImsStatus.Preparation -> {
                this.imsRequestedOn = null
                this.imsInstallDate = null
                this.imsStatus = ImsStatus.Preparation
            }

            ImsStatus.Requested -> {
                this.imsStatus = newImsState
                imsRequestedOn = LocalDate.now()
                this.imsInstallDate = null
            }

            ImsStatus.Planned -> {
                require(this.imsStatus == ImsStatus.Requested)
                this.imsStatus = newImsState
                this.imsInstallDate = installDate
            }

            ImsStatus.Active -> {
                require(this.imsStatus == ImsStatus.Planned)
                this.imsStatus = newImsState
            }
        }
    }

    fun changeInstallStatus(newStatus: InstallStatus) {
        if (newStatus == InstallStatus.Active) {
            require(this.imsStatus == ImsStatus.Active)
        }
        installStatus = newStatus
    }
}
