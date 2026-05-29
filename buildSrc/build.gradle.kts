plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
}

dependencies {
    plugin(libs.plugins.kotlin.jvm)
    plugin(libs.plugins.kotlin.kapt)
    plugin(libs.plugins.kotlin.jpa)
    plugin(libs.plugins.kotlin.spring)
    plugin(libs.plugins.kotlin.dokka)
    plugin(libs.plugins.kotlin.dokkaJavadoc)
    plugin(libs.plugins.springBoot)
    plugin(libs.plugins.springDependencies)
    plugin(libs.plugins.gitVersioning)
    plugin(libs.plugins.gradleNode)
}

private fun DependencyHandlerScope.plugin(plugin: Provider<PluginDependency>) {
    implementation(plugin.map {
        val groupId = it.pluginId
        val artifactId = "${it.pluginId}.gradle.plugin"
        val version = it.version.requiredVersion
        "$groupId:$artifactId:$version"
    })
}
