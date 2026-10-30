package de.kiwi.storage

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.test.context.ActiveProfiles

@MustBeDocumented
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@DataJpaTest(
    showSql = true,
    properties = [
        "spring.test.database.replace=NONE",
        "spring.datasource.url=jdbc:tc:mariadb:latest:///springboot",
        "spring.liquibase.enabled=true",
        "spring.liquibase.change-log=classpath:db/changelog/db.storage-master.xml"
    ],
)
annotation class StorageDataJpaTest

/**
 * Test application relying on the Kiwi storage auto-configuration, without component scanning.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ActiveProfiles("storage-test")
class StorageTestApplication
