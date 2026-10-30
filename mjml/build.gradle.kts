import com.github.gradle.node.npm.task.NpmTask

plugins {
    id("de.kiwi.common-conventions")
    id("de.kiwi.node-js-conventions")
    id("de.kiwi.maven-publish-conventions")
}

dependencies {
    // The MJML module implements the MailTemplateRenderer SPI of the framework module
    api(project(":bundle"))
    implementation(libs.bundles.graalvm.js)
    implementation("org.springframework.boot:spring-boot-starter")

    // Annotation Processing
    kapt("org.springframework.boot:spring-boot-configuration-processor")

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
    inputs.files("index.js", "polyfills.js", "package.json", "rolldown.config.mjs")
    outputs.dir("$projectDir/dist")

    dependsOn(tasks.npmInstall)
}

tasks.clean {
    dependsOn(npmCleanTask)
}

sourceSets {
    main {
        resources {
            srcDir(npmBuildTask)
        }
    }
}
