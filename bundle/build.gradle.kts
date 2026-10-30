plugins {
    id("de.kiwi.common-conventions")
    id("de.kiwi.antlr-conventions")
    id("de.kiwi.maven-publish-conventions")
}

dependencies {
    // Framework API Module
    api(project(":api"))

    // Spring Boot Dependencies
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("com.samskivert:jmustache")

    // Optional Dependencies
    compileOnly("org.springframework.boot:spring-boot-starter-actuator")

    // (Optional) Spring Security web integration, the Spring Security core is provided by the API module
    compileOnly("org.springframework.security:spring-security-web")
    compileOnly("org.springframework.security:spring-security-config")
    compileOnly("org.springframework.boot:spring-boot-security")

    // (Optional) SpringDoc dependency
    compileOnly(libs.springdoc.openapi.starter.webmvc.api)

    // Annotation Processing
    kapt("org.springframework.boot:spring-boot-configuration-processor")
    kapt("org.hibernate.orm:hibernate-processor")

    // Testing
    testImplementation(project(":mjml"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(libs.springdoc.openapi.starter.webmvc.api)
    testImplementation("org.springframework.security:spring-security-config")
    testImplementation("org.springframework.boot:spring-boot-security")
    testImplementation(libs.mockito.kotlin)
    testImplementation("com.samskivert:jmustache")
    testRuntimeOnly("org.springframework.boot:spring-boot-testcontainers")
    testRuntimeOnly("org.testcontainers:testcontainers-mariadb")
    testRuntimeOnly("org.mariadb.jdbc:mariadb-java-client")
    testRuntimeOnly("com.h2database:h2")

    kaptTest("org.hibernate.orm:hibernate-processor")
}

configurations {
    api {
        // Exclude antlr from api dependency scope to exclude "org.antlr:antlr4" tool dependency from runtime
        // This is a known bug workaround, see: https://github.com/gradle/gradle/issues/820#issuecomment-1585814999
        val antlrConfig = antlr.get()
        setExtendsFrom(extendsFrom.filterNot { it == antlrConfig })
    }
}

tasks.javadoc {
    // Exclude ANTLR generated sources from javadoc
    exclude("**/de/kiwi/framework/grammar/**")
}

kiwiMavenPublish {
    // Artifact named after the "de.kiwi.framework" package, instead of the "bundle" project name
    artifactId = "kiwi-framework"
    pom {
        description = "Kiwi Framework core module, implementing the API module"
    }
}
