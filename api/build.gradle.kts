plugins {
    id("de.kiwi.common-conventions")
    id("de.kiwi.maven-publish-conventions")
}

dependencies {
    // Types of these libraries appear in the public API
    api("org.springframework.security:spring-security-core")
    api("org.springframework.data:spring-data-jpa")
    api("jakarta.persistence:jakarta.persistence-api")
    api("com.fasterxml.jackson.core:jackson-annotations")
    api("tools.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")

    // Optional: Hibernate-specific subtype attribute resolution, falls back to standard JPA otherwise
    compileOnly("org.hibernate.orm:hibernate-core")

    kapt("org.hibernate.orm:hibernate-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation(libs.mockito.kotlin)
    testImplementation("org.hibernate.orm:hibernate-core")
    testRuntimeOnly("com.h2database:h2")

    kaptTest("org.hibernate.orm:hibernate-processor")
}

kiwiMavenPublish {
    pom {
        description = "Kiwi Framework shared API module"
    }
}
