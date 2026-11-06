@file:Suppress("UnstableApiUsage")

rootProject.name = "aloud"

pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    google()
  }
}

dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
  }
}

include(":app")
