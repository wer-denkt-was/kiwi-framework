rootProject.name = "kiwi-server"

include(":api")
include(":bundle")
include(":mjml")
include(":storage")
include(":audit-log")

include(":demo:todo-app")
include(":demo:access-control-tests")

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
