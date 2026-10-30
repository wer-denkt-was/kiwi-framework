package de.kiwi.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

// Version catalog "libs" of the build applying the convention plugins, i.e. gradle/libs.versions.toml
private val Project.libsExtension
    get() = extensions
        .getByType<VersionCatalogsExtension>()
        .named("libs")

internal fun Project.findLibrary(alias: String): Provider<MinimalExternalModuleDependency> = libsExtension
    .findLibrary(alias)
    .orElseThrow { NoSuchElementException("No such library alias: $alias") }
