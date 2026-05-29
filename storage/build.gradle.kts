plugins {
    id("kiwi-common-convention")
    id("kiwi-maven-publish-convention")
}

dependencies {
    // Framework API Module
    api(project(":api"))

    // Spring Boot Dependencies
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.security:spring-security-core")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Springdoc OpenAPI
    implementation(libs.springdoc.openapi.starter.webmvc.api)

    // Amazon AWS SDK
    implementation(libs.aws.sdk.kotlin.s3)

    // Annotation Processing
    kapt("org.springframework.boot:spring-boot-configuration-processor")
    kapt("org.hibernate.orm:hibernate-jpamodelgen")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.3.1")
    testImplementation("org.liquibase:liquibase-core")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:mariadb")
    testImplementation("org.testcontainers:minio")
    testRuntimeOnly("org.mariadb.jdbc:mariadb-java-client")
    kaptTest("org.hibernate.orm:hibernate-jpamodelgen")
}
