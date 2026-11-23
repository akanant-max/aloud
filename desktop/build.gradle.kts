import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  kotlin("jvm") version "2.4.21"
  id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
  id("org.jetbrains.compose") version "1.12.2"
}

/** Builds on GitHub are numbered; the installer's version follows, so each one upgrades the last. */
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val appVersion = "1.0.$buildNumber"

kotlin {
  jvmToolchain(21)
}

dependencies {
  implementation(compose.desktop.currentOs)
  implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
  implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
  implementation("com.materialkolor:material-kolor:5.0.1")
  implementation("org.xerial:sqlite-jdbc:3.50.3.0")
  implementation("org.jsoup:jsoup:1.21.1")
  implementation("org.apache.pdfbox:pdfbox:3.0.5")
  implementation("org.apache.commons:commons-compress:1.27.1")
  // sherpa-onnx's Java API, for the natural voices. Fetched by the build from its GitHub release;
  // its native libraries ship in resources/windows/sherpa.
  implementation(files("libs/sherpa-onnx-jvm-1.13.7.jar"))

  testImplementation(kotlin("test"))
  testImplementation("junit:junit:4.13.2")
  testImplementation("com.google.truth:truth:1.4.4")
}

compose.desktop {
  application {
    mainClass = "com.akhouri.aloud.MainKt"
    jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Daloud.version=$appVersion")

    nativeDistributions {
      targetFormats(TargetFormat.Msi)
      packageName = "Aloud"
      packageVersion = appVersion
      description = "Reads your books aloud"
      vendor = "Aloud"
      // The whole runtime, so nothing a library reaches for at run time is missing.
      includeAllModules = true
      // The speech engine, the bundled voice and the media-controls helper ship beside the app.
      appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

      windows {
        iconFile.set(project.file("icon.ico"))
        menuGroup = "Aloud"
        menu = true
        shortcut = true
        // Installs for the current user, so no administrator prompt.
        perUserInstall = true
        dirChooser = false
        // Fixed, so a newer installer replaces the installed app instead of sitting beside it.
        upgradeUuid = "5b0c6d1e-6c43-4d6a-9a3e-2f7d9c1a8e41"
      }
    }
  }
}

tasks.test {
  useJUnit()
}
