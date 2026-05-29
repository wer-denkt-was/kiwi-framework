// Kiwi-Server buildSrc project
rootProject.name = "buildSrc"

// Reuse main-project library-versions catalog
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
