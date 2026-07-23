// Plugin versions are pinned in gradle.properties (single source of truth). Declaring
// them here means build.gradle.kts applies the plugins without repeating versions.
pluginManagement {
    val kotlinVersion = providers.gradleProperty("kotlinVersion").get()
    val shadowVersion = providers.gradleProperty("shadowVersion").get()
    plugins {
        kotlin("jvm") version kotlinVersion
        kotlin("kapt") version kotlinVersion
        id("com.gradleup.shadow") version shadowVersion
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "mkvtool"
