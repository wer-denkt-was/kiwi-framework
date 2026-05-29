package de.kiwi.storage

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
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

@SpringBootApplication(scanBasePackages = ["de.kiwi.storage"])
@EntityScan(basePackages = ["de.kiwi.storage.domain"])
@ActiveProfiles("storage-test")
class StorageTestApplication
