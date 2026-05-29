plugins {
    id("kiwi-common-convention")
    id("kiwi-maven-publish-convention")
}

dependencies {
    compileOnly("org.springframework.security:spring-security-core")
    compileOnly("org.springframework.data:spring-data-jpa")
    compileOnly("jakarta.persistence:jakarta.persistence-api")
    compileOnly("com.fasterxml.jackson.core:jackson-annotations")
    compileOnly("com.fasterxml.jackson.core:jackson-databind")
    compileOnly("com.fasterxml.jackson.core:jackson-core")
    implementation("org.slf4j:slf4j-api")

    kapt("org.hibernate.orm:hibernate-jpamodelgen")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.3.1")
    testImplementation("org.hibernate.orm:hibernate-core")
    testRuntimeOnly("com.h2database:h2")

    kaptTest("org.hibernate.orm:hibernate-jpamodelgen")
}

kiwiMavenPublish {
    pom {
        description = "Kiwi Framework shared API module"
    }
}
