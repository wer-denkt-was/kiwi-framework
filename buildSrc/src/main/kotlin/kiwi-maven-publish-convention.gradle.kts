plugins {
    signing
    `maven-publish`
    id("org.jetbrains.dokka")
    id("org.jetbrains.dokka-javadoc")
}

abstract class KiwiMavenPublishExtension @Inject constructor(factory: ObjectFactory) {
    val artifactId = factory.property<String>()
    val pom = factory.newInstance<KiwiMavenPublishPomConfig>()
    fun pom(config: Action<KiwiMavenPublishPomConfig>) = config.execute(pom)
}

interface KiwiMavenPublishPomConfig {
    val name: Property<String>
    val description: Property<String>
}

// Define an extension for configuring the Kiwi Maven-Publish Convention
val kiwiMavenPublishExtension = extensions.create<KiwiMavenPublishExtension>("kiwiMavenPublish")
kiwiMavenPublishExtension.artifactId.convention("kiwi-${project.name}")
kiwiMavenPublishExtension.pom.name.convention("${project.group}:${project.name}")
kiwiMavenPublishExtension.pom.description.convention("Kiwi-Framework ${project.name} module")

// Add Dokka HTML to Javadoc jar file
tasks.named<Jar>("javadocJar") {
    val dokkaHtml = tasks.dokkaGeneratePublicationHtml
    dependsOn(dokkaHtml)
    from(dokkaHtml.flatMap { it.outputDirectory })
}

// Fix duplicates-conflict
tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

// Setup artifact signing using GPG signing keys
signing {
    val signingKey = providers.environmentVariable("GPG_SIGNING_KEY")
    val signingPass = providers.environmentVariable("GPG_PASSPHRASE")

    if (signingKey.isPresent && signingPass.isPresent) {
        val publishing = extensions.getByType<PublishingExtension>()
        useInMemoryPgpKeys(signingKey.get(), signingPass.get())
        sign(publishing.publications)
    }
}

// Build directory containing a Maven Repository Layout of the current module build artifacts
private val mavenBundleDirectory = layout.buildDirectory.dir("maven-bundle")
private val mavenBundleName = "mavenBundle"
private val kiwiPublicationName = "kiwi"

// Setup maven-artifact publishing
publishing {
    // Create "kiwi" Maven publication
    publications {
        create<MavenPublication>(kiwiPublicationName) {
            from(components["java"])
        }
    }

    // Register repositories for publishing
    repositories {
        mavenLocal()
        maven {
            name = mavenBundleName
            url = uri(mavenBundleDirectory)
        }
    }

    // Apply common properties to all maven publications
    publications.withType<MavenPublication>().configureEach {
        groupId = project.group.toString()
        artifactId = kiwiMavenPublishExtension.artifactId.get()

        pom {
            name = kiwiMavenPublishExtension.pom.name
            description = kiwiMavenPublishExtension.pom.description
            url = "https://github.com/wer-denkt-was/kiwi-framework"
            inceptionYear = "2025"
            licenses {
                license {
                    name = "EUPL-1.2"
                    url = "https://eupl.eu/1.2/en"
                    comments = "European Union Public License 1.2"
                    distribution = "repo"
                }
            }
            organization {
                name = "werdenktwas GmbH"
                url = "https://werdenktwas.de/"
            }
            developers {
                developer {
                    id = "dahmen"
                    name = "Alexander Dahmen"
                    email = "dahmen@werdenktwas.de"
                    organization = "werdenktwas GmbH"
                }
            }
            contributors {
                contributor {
                    name = "Dr. Tobias Klug"
                    email = "klug@werdenktwas.de"
                    organization = "werdenktwas GmbH"
                }
                contributor {
                    name = "Zoey Felicia Feld"
                    email = "feld@werdenktwas.de"
                    organization = "werdenktwas GmbH"
                }
                contributor {
                    name = "Dr. Steffen Schnitzer"
                    email = "schnitzer@werdenktwas.de"
                    organization = "werdenktwas GmbH"
                }
            }
            scm {
                url = "https://github.com/wer-denkt-was/kiwi-framework"
                connection = "scm:git:https://github.com/wer-denkt-was/kiwi-framework.git"
                developerConnection = "scm:git:https://github.com/wer-denkt-was/kiwi-framework.git"
            }
        }
    }
}

private fun String.capitalized() = replaceFirstChar { it.uppercase() }

// Create a ZIP-archive of the maven-bundle build directory,
// which can be published using the Sonatype Central Publish Portal UI/API
// https://central.sonatype.org/publish/publish-portal-upload/
tasks.register<Zip>("bundleMavenCentralArchive") {
    group = "publishing"
    description = "Create a Sonatype Central maven-bundle ZIP archive"

    val kiwiPublication = "${kiwiPublicationName}Publication".capitalized()
    val mavenBundleRepository = "${mavenBundleName}Repository".capitalized()
    val publishTaskName = "publish${kiwiPublication}To${mavenBundleRepository}"
    dependsOn(tasks.named(publishTaskName))

    archiveFileName = "${project.name}-${project.version}-maven-bundle.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    from(mavenBundleDirectory) {
        include("**/${project.version}/*")
        exclude("**/maven-metadata.xml")
        exclude("**/maven-metadata.xml.*")
    }
}
