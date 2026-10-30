package de.kiwi

import de.kiwi.gradle.MockitoAgentArgumentProvider
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    kotlin("jvm")
    kotlin("kapt")
    kotlin("plugin.jpa")
    kotlin("plugin.spring")
}

group = rootProject.group
version = rootProject.version

repositories {
    mavenCentral()
}

java {
    withJavadocJar()
    withSourcesJar()
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        javaParameters = true
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.Embeddable")
    annotation("jakarta.persistence.MappedSuperclass")
}

kapt {
    correctErrorTypes = true
}

private val mockitoAgent = configurations.create("mockitoAgent")

tasks.test {
    useJUnitPlatform()
    jvmArgumentProviders.add(MockitoAgentArgumentProvider(mockitoAgent))
}

dependencies {
    // Spring Boot dependency versions, published as platform dependency in the POM and Gradle module metadata.
    // Annotation-processor and agent configurations are resolved separately, so they need the platform as well.
    val springBootBom = platform(SpringBootPlugin.BOM_COORDINATES)
    api(springBootBom)
    kapt(springBootBom)
    kaptTest(springBootBom)
    mockitoAgent(springBootBom)

    implementation("org.jetbrains.kotlin:kotlin-reflect")

    mockitoAgent("org.mockito:mockito-core") { isTransitive = false }
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
