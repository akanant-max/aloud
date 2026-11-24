package com.akhouri.aloud.util

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window

/**
 * Paints the Windows title bar in the app's own colours, so it runs on from the screen below it
 * instead of standing out in the accent colour, while keeping the real Windows 11 minimise,
 * maximise and close buttons (with Snap layouts on hover).
 *
 * Uses DWM attributes from Windows 11; on Windows 10 only the dark mode part applies.
 */
object WindowChrome {

  private interface Dwm : StdCallLibrary {
    fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: Pointer, size: Int): Int
  }

  private val dwm: Dwm? by lazy {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return@lazy null
    runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull()
  }

  private const val USE_IMMERSIVE_DARK_MODE = 20
  private const val BORDER_COLOR = 34
  private const val CAPTION_COLOR = 35
  private const val TEXT_COLOR = 36

  /** Colours are 0xRRGGBB. */
  fun apply(window: Window, caption: Int, text: Int, dark: Boolean) {
    val api = dwm ?: return
    runCatching {
      if (!window.isDisplayable) return
      val hwnd = WinDef.HWND(Native.getWindowPointer(window) ?: return)
      fun set(attribute: Int, value: Int) {
        val ref = IntByReference(value)
        api.DwmSetWindowAttribute(hwnd, attribute, ref.pointer, 4)
      }
      set(USE_IMMERSIVE_DARK_MODE, if (dark) 1 else 0)
      set(CAPTION_COLOR, colorRef(caption))
      set(BORDER_COLOR, colorRef(caption))
      set(TEXT_COLOR, colorRef(text))
    }
  }

  /** Windows wants 0x00BBGGRR. */
  private fun colorRef(rgb: Int): Int {
    val r = (rgb shr 16) and 0xFF
    val g = (rgb shr 8) and 0xFF
    val b = rgb and 0xFF
    return (b shl 16) or (g shl 8) or r
  }
}
