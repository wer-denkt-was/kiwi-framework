plugins {
    id("kiwi-common-convention")
    id("kiwi-maven-publish-convention")
}

dependencies {
    // Framework API Module
    implementation(project(":api"))

    // Spring Boot Dependencies
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Declare kotlinx.serialization dependency, but depends on provided libraries by other modules
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json")

    // Annotation Processing
    kapt("org.springframework.boot:spring-boot-configuration-processor")
    kapt("org.hibernate.orm:hibernate-jpamodelgen")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.3.1")
    testImplementation("org.liquibase:liquibase-core")
    testRuntimeOnly("org.springframework.boot:spring-boot-testcontainers")
    testRuntimeOnly("org.testcontainers:mariadb")
    testRuntimeOnly("org.mariadb.jdbc:mariadb-java-client")
}
