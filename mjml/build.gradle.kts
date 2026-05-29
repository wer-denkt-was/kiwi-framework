import com.github.gradle.node.npm.task.NpmTask

plugins {
    id("kiwi-common-convention")
    id("kiwi-node-js-convention")
    id("kiwi-maven-publish-convention")
}

dependencies {
    implementation(project(":bundle"))
    implementation(libs.bundles.graalvm.js)
    implementation("org.springframework.boot:spring-boot-starter")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

val npmCleanTask = tasks.register<NpmTask>("npmRunClean") {
    group = "npm"
    args = listOf("run", "clean")
    dependsOn(tasks.npmInstall)
}

val npmBuildTask = tasks.register<NpmTask>("npmRunBuild") {
    group = "npm"
    args = listOf("run", "build")

    inputs.dir("node_modules")
    inputs.files("index.js")
    outputs.dir("$projectDir/dist")

    dependsOn(tasks.npmInstall)
}

tasks.clean {
    dependsOn(npmCleanTask)
}

sourceSets {
    java {
        main {
            resources {
                srcDir(npmBuildTask)
            }
        }
    }
}
