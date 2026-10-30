package de.kiwi

// Convention for Spring Boot applications, e.g. the demo applications.
// Applications use the Spring dependency-management plugin, which also manages the versions of configurations
// that don't extend the platform dependency of the common conventions, e.g. "developmentOnly".
plugins {
    id("de.kiwi.common-conventions")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}
