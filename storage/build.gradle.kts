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

    // (Optional) SpringDoc OpenAPI annotations
    compileOnly(libs.springdoc.openapi.starter.webmvc.api)

    // Amazon AWS SDK
    implementation(libs.aws.sdk.kotlin.s3)

    // Annotation Processing
    kapt("org.springframework.boot:spring-boot-configuration-processor")
    kapt("org.hibernate.orm:hibernate-processor")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation(libs.mockito.kotlin)
    testImplementation("org.springframework.boot:spring-boot-starter-liquibase")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mariadb")
    testImplementation("org.testcontainers:testcontainers-minio")
    testRuntimeOnly("org.mariadb.jdbc:mariadb-java-client")
    testRuntimeOnly("com.h2database:h2")
    kaptTest("org.hibernate.orm:hibernate-processor")
}
