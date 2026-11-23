// The Windows app is a build of its own, so nothing here can affect the Android build.
rootProject.name = "aloud-desktop"

pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    google()
  }
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
    google()
  }
}
