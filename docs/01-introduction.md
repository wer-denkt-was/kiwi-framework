# 1. Introduction - Kiwi Server

The Kiwi-Server Framework provides a foundation for web-server application development based on
the [Spring Framework](https://spring.io/projects/spring-framework), [SpringBoot](https://spring.io/projects/spring-boot),
and the [Kotlin Programming Language](https://kotlinlang.org/).

This chapter explains the basic Kiwi Server concepts, and how to set up a new project.

```
| |  (_)        (_)
| | _____      ___     __ _
| |/ / \ \ /\ / / |   /  ('>-
|   <| |\ V  V /| |   \__/
|_|\_\_| \_/\_/ |_|    L\_
```

## What you need to know

Prerequisites required to use this framework to its full extent include:

- Programming in Kotlin, see ["Get started with Kotlin"](https://kotlinlang.org/docs/getting-started.html)
- Using the Spring Framework and its associated Projects
    - [Spring Core](https://docs.spring.io/spring-framework/reference/core.html)
    - [Spring Data JPA](https://docs.spring.io/spring-data/jpa/reference/jpa.html)
    - [Spring Web Servlet](https://docs.spring.io/spring-framework/reference/web.html)
    - [Spring Security](https://docs.spring.io/spring-security/reference/index.html)
    - [Spring Boot](https://docs.spring.io/spring-boot/index.html)
- Familiarity with underlying Java Libraries and Jakarta APIs
    - Database communication using [JDBC](https://jakarta.ee/specifications/connectors/2.1/), and entity persistence
      using [JPA](https://jakarta.ee/specifications/persistence/3.1/)
    - JPA Queries with
      the [Criteria API](https://jakarta.ee/learn/docs/jakartaee-tutorial/current/persist/persistence-criteria/persistence-criteria.html)
    - [Hibernate ORM](https://hibernate.org/orm/) as JPA provider
    - [Liquibase](https://www.liquibase.com/) for database migration
- For further tutorials and lookup, [baeldung.com](https://www.baeldung.com/start-here) is a recommended resource
    - [Learn Spring](https://www.baeldung.com/spring-tutorial)
    - [Learn Spring Data](https://www.baeldung.com/persistence-with-spring-series)
    - [Learn Spring Security](https://www.baeldung.com/security-spring)
    - [Learn JPA Hibernate](https://www.baeldung.com/learn-jpa-hibernate)
    - [Learn JPA Criteria Queries](https://www.baeldung.com/hibernate-criteria-queries)
    - [Learn Liquibase](https://www.baeldung.com/liquibase-refactor-schema-of-java-app)

A diagram visualising the technology stack for Kiwi-Server can be found
in [Appendix: "Kiwi-Server Framework Stack"](./99-appendix.md#kiwi-server-framework-stack).

## Kiwi-Server Framework Modules

The Kiwi-Server Framework is composed of modules, which can be included in a Java project as Maven artifact
dependencies. The group ID for these artifacts is `de.kiwi-framework`.

- `de.kiwi-framework:kiwi-api` The Kiwi API module contains interfaces and classes shared among all other modules in the
  framework, and is required for the application to interact with the functionality provided by the framework. All other
  modules depend on this API.
- `de.kiwi-framework:kiwi-bundle` The Kiwi Bundle module implements interfaces specified by the API module, and provides
  most of the core functionality of the framework. This module should be included in the main project integration
  module, but does not need to be included in every project application module.
- `de.kiwi-framework:kiwi-mjml` The Kiwi MJML module is a plug-in that extends the e-mail template rendering
  capabilities of Kiwi, allowing it to render [MJML Templates](https://mjml.io/) into HTML e-mail content.
- `de.kiwi-framework:kiwi-audit-log` The Kiwi Audit-Log module provides an audit-log-entry entity, a database schema,
  and a service implementation for audit-logging as required by the Kiwi API module `AuditLoggerService` interface.
- `de.kiwi-framework:kiwi-storage` The Kiwi Storage module provides a file entity, a database schema, and multiple
  service implementations for the API module `FileStorageService` interface.

## Project Application Architecture

Kiwi-Server is an opinionated framework, which comes with its own best practices and paradigms based on principles
like [Domain-Driven Design](https://en.wikipedia.org/wiki/Domain-driven_design),
the [SOLID Principles](https://en.wikipedia.org/wiki/SOLID),
[CQRS](https://en.wikipedia.org/wiki/Command_Query_Responsibility_Segregation),
and [Hexagonal Architecture](https://en.wikipedia.org/wiki/Hexagonal_architecture_(software)).

As such, the structure of applications built with Kiwi Server should adhere to the following structure:

- Each application module contains a base package, named after the module's group-id
- The base package contains a `domain` package, containing the application core
  (see [Chapter 2: "Domain Entities and Schema"](./02-domain-schema.md))
- The base package contains an `application` package, containing the application handlers, use-case logic, service- and
  repository-interfaces (see ["Chapter 3: Application Commands, Queries and Events"](./03-application-command-query.md))
- The base package contains an `infrastructure` package, containing the application infrastructure, configuration
  classes, and service implementations
  (see ["Chapter 5: Infrastructure and Integration"](./05-infrastructure-integration.md))
- The base package contains a `web` package, containing the application web-controllers and controller-advice classes
  (see ["Chapter 6: Web MVC and Endpoints"](./06-web-mvc-endpoints.md))

For a full overview of the recommended architecture for a project application module, see
[Appendix: "Application Module Architecture"](./99-appendix.md#application-module-architecture).
All components shown in this diagram will be explained throughout the chapters of this guide.

## Set up a new Project Application

Here is how you can start development on a new project application:

### Start with Spring Initializr

The easiest way to begin a new project is using the [**Spring Initializr**](https://start.spring.io/) service.
This tool, which may be integrated into your IDE, will help you quickly set up an initial project to begin with.

Recommended initializr settings:

- Project: **Gradle - Kotlin**
- Language: **Kotlin**
- Spring Boot: Latest 3.5.x, check for `spring-boot.version`
  in [gradle/libs.versions.toml](../gradle/libs.versions.toml) for a compatible version
- Packaging: **jar**
- Java: **21** or above

In the following sections, it is assumed the project group-id is `com.example` and name/artifact-id is `example`,
with the base package also defined as `com.example`.

Starter dependencies for a Kiwi-Server application:

- Development:
    - **Spring Boot DevTools** for dynamic restarting on compile
    - **Docker Compose Support** for container support during development
    - **Spring Configuration Processor** for auto-completion of application config properties
- Web:
    - **Spring Web** for MVC controllers and embedded Tomcat web server
    - (Optional) **WebSocket** for servlet-based websocket applications using STOMP
- Security:
    - (Optional) **Spring Security** for user authentication, and basic authorization
    - (Optional) **OAuth2 Client** for OAuth2/OpenID-Connect integration
    - (Optional) **OAuth2 Resource Server** for integrating external JWT authentication
    - (Optional) **Spring LDAP** for integrating user authentication against LDAP directories
- SQL:
    - **Spring Data JPA** for persistence and querying using JPA/Hibernate
    - **Liquibase Migration** for application-controlled database migration
    - Your preferred database driver, usually the **MySQL**, **MariaDB** or **PostgreSQL Driver**
- I/O:
    - (Optional) **Java Mail Sender** for sending e-mails over SMTP
- Monitoring:
    - (Optional) **Spring Boot Actuator** for health- and monitoring-endpoints
    - Observability adapters like Prometheus can be added for integrating other monitoring tools
- Testing:
    - **Testcontainers** to support temporary service containers during unit-tests
    - JUnit test-container integration with `org.testcontainers:junit-jupiter`
    - Specific test-container integrations for your test-cases, e.g. `org.testcontainers:mysql`
- Further dependencies can be used at your discretion, but are not specifically supported by Kiwi Server

After generating a basic SpringBoot project and opening it in your IDE, open the `build.gradle.kts` file in the project
root directory.

### Integrate the Kiwi-Server Framework

To use the Kiwi-Server Framework for your project, add the following dependencies:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    // (2) Choose a Kiwi Server version; this can be a property in gradle.properties
    val kiwiVersion = "..." // Either literal, or project.property("kiwi.version") and then specify in gradle.properties

    // (3) Add Kiwi artifacts as dependencies
    // API Module: Required in *every* application module, provides Kiwi functionality
    implementation("de.kiwi-framework:kiwi-api:$kiwiVersion")

    // Bundle Module: Only required in main integration module, implements Kiwi functionality
    implementation("de.kiwi-framework:kiwi-bundle:$kiwiVersion")

    // [Optional] MJML Module: Provides a plug-in MJML e-mail template renderer implementation
    implementation("de.kiwi-framework:kiwi-mjml:$kiwiVersion")

    // [Optional] Storage Module: Provides FileStorageService implementations, configuration, and file entity
    implementation("de.kiwi-framework:kiwi-storage:$kiwiVersion")

    // [Optional] Audit-Log Module: Provides AuditLoggerService implementation, and audit-log-entry entity
    implementation("de.kiwi-framework:kiwi-audit-log:$kiwiVersion")

    // [...] Other dependencies
}
```

> To see which specific version of Kiwi-Server is available, check the
> `maven-metadata.xml` file provided by the Maven Repository hosted on Maven Central.

To generate static JPA Entity Metamodels, add [KAPT](https://kotlinlang.org/docs/kapt.html) and the
[Hibernate JPA-Modelgen](https://docs.jboss.org/hibernate/stable/jpamodelgen/reference/en-US/html_single/):

```kotlin
plugins {
    // (1) Add KAPT for kotlin annotation processing
    kotlin("kapt") version "[...]"
}

dependencies {
    // [...] Other dependencies

    // (2) Add the Hibernate JPA-Modelgen annotation processor using KAPT
    kapt("org.hibernate.orm:hibernate-jpamodelgen")
}
```

Then import the Kiwi configuration into the project application.

First, edit the main application class.
In the example application, this is `src/main/kotlin/com/example/ExampleApplication.kt`:

```kotlin
package com.example

import de.kiwi.auditLog.KiwiAuditLogConfiguration
import de.kiwi.framework.KiwiFrameworkConfiguration
import de.kiwi.storage.KiwiStorageConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication
// (1) Import Kiwi module configuration classes into application
@Import(
    KiwiFrameworkConfiguration::class, // Kiwi Bundle module
    KiwiAuditLogConfiguration::class, // (Optional) Kiwi Audit-Log module
    KiwiStorageConfiguration::class, // (Optional) Kiwi Storage module
)
// (2) Enable JPA entity scanning and repositories
@EntityScan
@EnableJpaRepositories
class ExampleApplication

fun main(args: Array<String>) {
    runApplication<ExampleApplication>(*args)
}
```

Second, create the Liquibase migration master-file: `src/main/resources/db/changelog/db.changelog-master.yaml`.
This file must include the migrations of any Kiwi modules included in the main application.

```yaml
databaseChangeLog:
  ## (1) Add Kiwi database migrations
  # Kiwi Audit-Log migrations, if module is included in application
  - include: { file: "classpath:db/changelog/db.audit-log-master.xml" }
  # Kiwi Storage migrations, if module is included in application
  - include: { file: "classpath:db/changelog/db.storage-master.xml" }
  ## (2) Add other application migrations here, either directly or as file-includes
  #- changeSet: ...
  #- include: { file: "classpath:db/changelog/..." }
```

For a nicer configuration syntax, replace the generated `src/main/resources/application.properties` file
with a YAML configuration file: `src/main/resources/application.yml`.

> Note: If you don't use YAML for application configuration, the equivalent properties are written as property-paths
> joined with dots. For example, `spring: { application: { name: "example" } }` is equivalent to
> the property `spring.application.name=example`.

Third, in the application configuration `src/main/resources/application.yml`, set up basic config properties
and the Kiwi Framework modules:

```yaml
## (1) Basic Spring, Spring-Data JPA/Hibernate properties
spring:
  application:
    name: example
  # Spring-Data JPA/Hibernate configuration
  jpa:
    # Set to `true` to enable SQL console logging
    show-sql: false
    # Always keep this `false`, as the associated feature is not required and could degrade performance
    # (See the "OSIV Anti-Pattern": https://vladmihalcea.com/the-open-session-in-view-anti-pattern/ )
    open-in-view: false
    # Validate database schema on startup
    # NOTE: If configured wrong, Hibernate may attempt to create its own database schema,
    # or even drop or break the existing schema. This is a notorious foot-gun!
    hibernate.ddl-auto: validate

## (2) Kiwi Bundle configuration properties
kiwi:
  jwt:
    # Secret key for framework JWTs
    # Can be generated in bash: head -c64 /dev/random | base64 -w0
    secret-key: "... insert secret-key here ..."
  ## (3) Kiwi Audit-Log configuration properties
  audit-log:
    enabled: true
  ## (4) Kiwi Storage configuration properties
  storage:
    # Choose a storage provider: "none", "data-uri", "s3", or "local"
    mode: local
    # Further configure the chosen storage provider if required
    #s3: ...
    #local: ...
```

> Note: Don't forget to generate a JWT secret key for your application, and configure it in
> `kiwi.jwt.secret-key`.  
> Alternatively, if you don't wish to use JWT-based features, you can disable the
> Kiwi-Server JWT service configuration by setting `kiwi.jwt.enabled=false`.

For a full reference of available Kiwi module configuration properties,
see [Chapter 5: "Kiwi configuration properties"](./05-infrastructure-integration.md#kiwi-configuration-properties).

Congratulations, you're now ready to start development on your new web-server application!

### First application run

To verify that everything works correctly, run the following command within the project root directory:

```shell
./gradlew bootRun
```

The application server should now be running, with a message confirming:

> Tomcat started on port 8080 (http) with context path '/'  
> Started ExampleApplicationKt in (...) seconds

If you now navigate to http://localhost:8080/, you'll see a whitelabel error page with status
`type=Not Found, status=404`. This means your application is running, but has no controller mappings yet.

To check if unit-tests are working correctly, run the following command within the project root directory:

```shell
./gradlew test
```

This will execute the application unit-tests, and should report a success message upon completion:

> BUILD SUCCESSFUL in (...)s

---

Next up, [Chapter 2: "Domain Entities and Schema"](./02-domain-schema.md) is about defining entities, entity schema, and
fieldsets to add data, logic and the domain of your application core.
