plugins {
    id("de.kiwi.common-conventions")
    id("de.kiwi.maven-publish-conventions")
}

dependencies {
    // Framework API Module
    api(project(":api"))

    // Spring Boot Dependencies
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("tools.jackson.module:jackson-module-kotlin")

    // Declare kotlinx.serialization dependency, but depends on provided libraries by other modules
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json")

    // Annotation Processing
    kapt("org.springframework.boot:spring-boot-configuration-processor")
    kapt("org.hibernate.orm:hibernate-processor")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-liquibase")
    testImplementation(libs.mockito.kotlin)
    testRuntimeOnly("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-mariadb")
    testImplementation("org.testcontainers:testcontainers-mysql")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.mariadb.jdbc:mariadb-java-client")
    testRuntimeOnly("com.mysql:mysql-connector-j")
    testRuntimeOnly("org.postgresql:postgresql")
    testRuntimeOnly("com.h2database:h2")
}
