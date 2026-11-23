package com.akhouri.aloud.util

import androidx.compose.ui.graphics.Color
import java.util.concurrent.TimeUnit

/** The few things Aloud reads from Windows' own settings: dark mode and the accent colour. */
object WindowsSystem {

  private const val PERSONALIZE =
    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"
  private const val DWM = "HKCU\\Software\\Microsoft\\Windows\\DWM"

  /** Whether apps are set to dark in Windows' colour settings. */
  fun prefersDark(): Boolean = readDword(PERSONALIZE, "AppsUseLightTheme")?.let { it == 0 } ?: false

  /** The accent colour chosen in Windows' personalisation settings. */
  fun accentColor(): Color? = readDword(DWM, "AccentColor")?.let { abgr ->
    Color(red = abgr and 0xFF, green = (abgr shr 8) and 0xFF, blue = (abgr shr 16) and 0xFF)
  }

  private fun readDword(key: String, value: String): Int? = runCatching {
    val process = ProcessBuilder("reg", "query", key, "/v", value)
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText()
    process.waitFor(3, TimeUnit.SECONDS)
    Regex("REG_DWORD\\s+0x([0-9a-fA-F]+)").find(output)?.groupValues?.get(1)?.toLong(16)?.toInt()
  }.getOrNull()
}
