plugins {
    base
    alias(libs.plugins.gitVersioning)
}

// Initialize group and version
group = "de.kiwi-framework"
version = "0.0.0-SNAPSHOT"

// Git version patterns
private val RELEASE_TAG = "release/(?<version>.+)"
private val RELEASE_CANDIDATE_TAG = "rc/(?<version>.+?)/(?<rc>.+)"
private val MAIN_BRANCH = "main|master|.*/(main|master)"
private val FEATURE_BRANCH = "feature/.+"
private val RELEASE_CHECKOUT = "release/.*"
private val RELEASE_CANDIDATE_CHECKOUT = "rc/.*"
private val MAIN_PATTERN = $$"${describe.tag.version}-${describe.distance}${dirty.snapshot}"
private val RC_PATTERN = $$"${describe.tag.version}-RC-${describe.tag.rc}-${describe.distance}${dirty.snapshot}"
private val HEAD_PATTERN = $$"${describe.tag.version}-HEAD-${commit.short}${dirty.snapshot}"

// Apply version from Git repository refs
gitVersioning.apply {
    // Default pattern for release-tags, e.g. "release/1.2.3"
    describeTagPattern = RELEASE_TAG
    describeTagFirstParent = false
    refs {
        // Main branch releases, e.g. branch "main" with tag "release/1.2.3" -> version = "1.2.3"
        branch(MAIN_BRANCH) {
            version = MAIN_PATTERN
        }
        // Release tag checkout releases (identical to main branch)
        tag(RELEASE_CHECKOUT) {
            version = MAIN_PATTERN
        }
        // Feature release-candidate versions, e.g. branch "feature/new-thing" with tag "rc/1.2.3/1" -> version = "1.2.3-RC-1"
        branch(FEATURE_BRANCH) {
            describeTagPattern = RELEASE_CANDIDATE_TAG
            version = RC_PATTERN
        }
        // Release-candidate tag checkout (identical to feature branch)
        tag(RELEASE_CANDIDATE_CHECKOUT) {
            describeTagPattern = RELEASE_CANDIDATE_TAG
            version = RC_PATTERN
        }
    }
    rev {
        // Checkout on commit without known ref -> version = "1.2.3-HEAD-{commit}"
        version = HEAD_PATTERN
    }
}

// Remove trailing "-0" (distance from tag) on exact match
version = version.let { it as? String }?.removeSuffix("-0") ?: version

// Create all-components bundle zip for publishing packaged distributions
tasks.register<Zip>("bundleAllComponentsMavenCentralArchive") {
    group = "publishing"
    description = "Assembles all the Maven Central Archives"

    destinationDirectory = layout.buildDirectory.dir("distributions")
    archiveFileName = "kiwi-components-${project.version}-maven-bundle.zip"

    subprojects.mapNotNull { it.tasks.findByName("bundleMavenCentralArchive") }.forEach { bundleSubtask ->
        dependsOn(bundleSubtask)
        bundleSubtask.outputs.files.forEach { file ->
            from(zipTree(file))
        }
    }
}

tasks.clean {
    delete(layout.buildDirectory)
}
