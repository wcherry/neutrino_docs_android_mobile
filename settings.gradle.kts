pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
plugins {
    // Lets the `jvmToolchain(17)` below download a JDK 17 where none is installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "NeutrinoDocs"
include(":core", ":app")

// The code every Neutrino Android app shares — E2EE, keyring, sign-in, the key screens and the
// `.docx` reader and writer — consumed by local path, so this repository must sit beside
// neutrino_shared_android (root CLAUDE.md, rule 2). Gradle substitutes `com.neutrino.shared:*` with
// the projects there.
includeBuild("../neutrino_shared_android")
