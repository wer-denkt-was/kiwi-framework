package de.kiwi.framework.application.generic

import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.test.context.jdbc.Sql

@MustBeDocumented
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@DataJpaTest(
    showSql = true,
    properties = [
        "spring.test.database.replace=NONE",
        "spring.datasource.url=jdbc:tc:mariadb:latest:///springboot",
    ],
)
@Sql(
    scripts = ["classpath:exampleSchema.sql"],
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS
)
@ComponentScan("de.kiwi.framework.example")
annotation class ExampleDataJpaTest
