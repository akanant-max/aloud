package com.akhouri.aloud

import androidx.compose.material3.Text
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

// Temporary: proves the Windows build, packaging and bundled files before the app lands.
fun main() = application {
  Window(onCloseRequest = ::exitApplication, title = "Aloud") {
    Text("Aloud is being built. Resources: " + System.getProperty("compose.application.resources.dir"))
  }
}
