import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.VersionConstraint
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

// Use libs.versions.toml version-catalog
private val Project.libsExtension
    get() = extensions
        .getByType<VersionCatalogsExtension>()
        .named("libs")

internal fun Project.findLibrary(alias: String): Provider<MinimalExternalModuleDependency> = libsExtension
    .findLibrary(alias)
    .orElseThrow { NoSuchElementException("No such library alias: $alias") }

internal fun Project.findVersion(alias: String): VersionConstraint = libsExtension
    .findVersion(alias)
    .orElseThrow { NoSuchElementException("No such version alias: $alias") }
