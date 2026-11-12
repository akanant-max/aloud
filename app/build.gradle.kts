plugins {
  // AGP 9 supplies Kotlin itself; applying kotlin.android alongside it is a fatal error.
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.ksp)
}

android {
  namespace = "com.akhouri.aloud"
  compileSdk = libs.versions.sdk.compile.get().toInt()

  defaultConfig {
    applicationId = "com.akhouri.aloud"
    minSdk = libs.versions.sdk.min.get().toInt()
    targetSdk = libs.versions.sdk.target.get().toInt()
    versionCode = 1
    versionName = "0.1.0"
  }

  // Every build has to carry the same signature, or Android refuses to install it over the
  // last one. The key is not kept in the repository: the build workflow writes it here from a
  // repository secret. Without it the build falls back to a throwaway key.
  val fixedKeystore = file("debug.keystore")
  signingConfigs {
    getByName("debug") {
      if (fixedKeystore.exists()) {
        storeFile = fixedKeystore
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
  }

  buildTypes {
    debug {
      applicationIdSuffix = ".debug"
    }
    release {
      isMinifyEnabled = false
      // Debug-signed so CI can produce an installable APK with no keystore.
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  buildFeatures {
    compose = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  packaging {
    resources.excludes += setOf(
      "META-INF/{AL2.0,LGPL2.1}",
      "META-INF/DEPENDENCIES",
      "META-INF/INDEX.LIST",
    )
  }

  lint {
    abortOnError = false
    checkReleaseBuilds = false
  }
}

kotlin {
  jvmToolchain(libs.versions.jvm.toolchain.get().toInt())
}

ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
  val composeBom = platform(libs.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.compose.material.icons)
  implementation(libs.compose.ui.tooling.preview)
  debugImplementation(libs.compose.ui.tooling)

  implementation(libs.androidx.core)
  implementation(libs.activity.compose)
  implementation(libs.navigation.compose)
  implementation(libs.lifecycle.runtime.compose)
  implementation(libs.lifecycle.viewmodel.compose)
  implementation(libs.lifecycle.service)

  implementation(libs.room.runtime)
  ksp(libs.room.compiler)

  implementation(libs.media3.session)
  implementation(libs.media3.common)

  implementation(libs.coroutines.core)
  implementation(libs.coroutines.android)
  implementation(libs.coroutines.guava)

  implementation(libs.documentfile)
  implementation(libs.datastore.preferences)
  implementation(libs.materialkolor)
  implementation(libs.coil)
  implementation(libs.pdfbox.android)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
  testImplementation(libs.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.room.testing)
}
