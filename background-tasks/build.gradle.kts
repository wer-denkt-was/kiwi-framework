plugins {
    id("de.kiwi.common-conventions")
    id("de.kiwi.maven-publish-conventions")
}

dependencies {
    // Framework API module
    api(project(":api"))

    // Spring Boot dependencies
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.security:spring-security-core")
    implementation("tools.jackson.module:jackson-module-kotlin")

    // Optional (provided by the application): RabbitMQ queue provider
    compileOnly("org.springframework.boot:spring-boot-starter-amqp")
    // Optional (provided by the application): Simp messaging provider (e.g. via spring-boot-starter-websocket)
    compileOnly("org.springframework:spring-messaging")
    // Optional (provided by the application): Management endpoint for monitoring and resuming tasks
    compileOnly("org.springframework.boot:spring-boot-actuator-autoconfigure")

    // Annotation processing
    kapt("org.springframework.boot:spring-boot-configuration-processor")
    kapt("org.hibernate.orm:hibernate-processor")

    // Testing
    testImplementation(project(":bundle"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.boot:spring-boot-starter-amqp")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-liquibase")
    testImplementation(libs.mockito.kotlin)
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mariadb")
    testImplementation("org.testcontainers:testcontainers-mysql")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-rabbitmq")
    testRuntimeOnly("org.mariadb.jdbc:mariadb-java-client")
    testRuntimeOnly("com.mysql:mysql-connector-j")
    testRuntimeOnly("org.postgresql:postgresql")
    testRuntimeOnly("com.h2database:h2")
}
