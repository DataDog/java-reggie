pluginManagement {
    repositories {
        providers.gradleProperty("gradlePluginProxy").orNull?.let { maven(it) }
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        providers.gradleProperty("mavenRepositoryProxy").orNull?.let { maven(it) }
        mavenCentral()
    }
}

rootProject.name = "reggie-build-logic"
