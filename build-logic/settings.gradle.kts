// Kiwi-Server build logic: convention plugins shared by the modules of the main build,
// included by the main build using "pluginManagement { includeBuild("build-logic") }"
rootProject.name = "build-logic"

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
    }

    // Reuse the version catalog of the main build
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
