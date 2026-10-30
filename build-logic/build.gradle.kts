plugins {
    `kotlin-dsl`
}

dependencies {
    // Gradle plugins applied by the convention plugins
    plugin(libs.plugins.kotlin.jvm)
    plugin(libs.plugins.kotlin.kapt)
    plugin(libs.plugins.kotlin.jpa)
    plugin(libs.plugins.kotlin.spring)
    plugin(libs.plugins.kotlin.dokka)
    plugin(libs.plugins.kotlin.dokkaJavadoc)
    plugin(libs.plugins.springBoot)
    plugin(libs.plugins.gradleNode)
}

/** Depend on the implementation of a Gradle plugin, using its plugin marker artifact */
fun DependencyHandlerScope.plugin(plugin: Provider<PluginDependency>) {
    implementation(plugin.map {
        val groupId = it.pluginId
        val artifactId = "${it.pluginId}.gradle.plugin"
        val version = it.version.requiredVersion
        "$groupId:$artifactId:$version"
    })
}
