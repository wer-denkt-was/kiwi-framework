package de.kiwi.testapp

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import org.springframework.data.jpa.repository.JpaRepository

/** Entity of an application using the Kiwi storage module, used for testing the storage auto-configuration */
@Entity
class OtherEntity(
    @Id
    @GeneratedValue
    val id: Long? = null,
)

interface OtherEntityRepository : JpaRepository<OtherEntity, Long>
