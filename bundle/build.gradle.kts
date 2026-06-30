plugins {
    id("kiwi-common-convention")
    id("kiwi-antlr-convention")
    id("kiwi-maven-publish-convention")
}

dependencies {
    // Framework API Module
    implementation(project(":api"))

    // Spring Boot Dependencies
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.samskivert:jmustache")

    // Optional Dependencies
    compileOnly("org.springframework.boot:spring-boot-starter-actuator")

    // Spring Security
    implementation("org.springframework.security:spring-security-core")
    implementation("org.springframework.security:spring-security-web")
    compileOnly("org.springframework.security:spring-security-config")

    // JSON Web Tokens
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // (Optional) SpringDoc dependency
    compileOnly(libs.springdoc.openapi.starter.webmvc.api)

    // Annotation Processing
    kapt("org.springframework.boot:spring-boot-configuration-processor")
    kapt("org.hibernate.orm:hibernate-jpamodelgen")

    // Testing
    testImplementation(project(":mjml"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.security:spring-security-config")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.3.1")
    testImplementation("com.samskivert:jmustache")
    testRuntimeOnly("org.springframework.boot:spring-boot-testcontainers")
    testRuntimeOnly("org.testcontainers:mariadb")
    testRuntimeOnly("org.mariadb.jdbc:mariadb-java-client")
    testRuntimeOnly("com.h2database:h2")

    kaptTest("org.hibernate.orm:hibernate-jpamodelgen")
}

// Include ANTLR generated grammar in source-set
sourceSets {
    main {
        java {
            srcDir(tasks.generateGrammarSource)
        }
    }
    test {
        java {
            srcDir(tasks.generateTestGrammarSource)
        }
    }
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
