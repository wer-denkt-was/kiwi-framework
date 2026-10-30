pluginManagement {
    // Convention plugins of this build, see build-logic/
    includeBuild("build-logic")
}

plugins {
    // Use the Foojay Toolchains plugin to automatically download JDKs required by subprojects
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "kiwi-server"

include(":api")
include(":bundle")
include(":mjml")
include(":storage")
include(":audit-log")

include(":demo:todo-app")
include(":demo:access-control-tests")
