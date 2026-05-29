plugins {
    kotlin("jvm")
    id("com.github.node-gradle.node")
}

repositories {
    mavenCentral()
}

node {
    download = true
    version = "22.21.1"
}
