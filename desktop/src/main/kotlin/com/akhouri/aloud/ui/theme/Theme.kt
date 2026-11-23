@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.akhouri.aloud.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp
import com.akhouri.aloud.data.ThemeColorScheme
import com.akhouri.aloud.data.ThemeMode
import com.akhouri.aloud.util.WindowsSystem
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.ktx.animateColorScheme

/*
 * The theme follows Voice by Paul Woitaschek (GPLv3): Material 3 Expressive, palettes grown
 * from a single seed colour, and Literata for words that come from a book.
 */

/** The deep blue the default palette is grown from. */
val AloudBlue = Color(0xFF003B7F)

/** Windows' accent colour, read once; null if it could not be read. */
val windowsAccent: Color? by lazy { WindowsSystem.accentColor() }

fun seedColor(scheme: ThemeColorScheme): Color = when (scheme) {
  ThemeColorScheme.Blue -> AloudBlue
  ThemeColorScheme.Accent -> windowsAccent ?: AloudBlue
  ThemeColorScheme.Lagoon -> Color(0xFF00696E)
  ThemeColorScheme.Forest -> Color(0xFF3B6D2A)
  ThemeColorScheme.Sunset -> Color(0xFFC4501F)
  ThemeColorScheme.Berry -> Color(0xFFA3305F)
  ThemeColorScheme.Lavender -> Color(0xFF6B4EB8)
}

private object FontFiles

/** Literata, for the words that come from the book: its title, author, chapters and text. */
val BookFamily: FontFamily by lazy {
  val bytes = FontFiles::class.java.getResourceAsStream("/fonts/literata.ttf")?.use { it.readBytes() }
  if (bytes == null) {
    FontFamily.Serif
  } else {
    FontFamily(listOf(400, 500, 600, 700).map { weight -> Font("literata-$weight", bytes, FontWeight(weight)) })
  }
}

fun TextStyle.bookText(): TextStyle = copy(fontFamily = BookFamily)

private val AloudTypography: Typography by lazy {
  Typography().run {
    copy(
      displayLarge = displayLarge.bookText(),
      displayMedium = displayMedium.bookText(),
      displaySmall = displaySmall.bookText(),
      headlineLarge = headlineLarge.bookText(),
      headlineMedium = headlineMedium.bookText(),
      headlineSmall = headlineSmall.bookText(),
      titleLarge = titleLarge.bookText(),
      displayLargeEmphasized = displayLargeEmphasized.bookText(),
      displayMediumEmphasized = displayMediumEmphasized.bookText(),
      displaySmallEmphasized = displaySmallEmphasized.bookText(),
      headlineLargeEmphasized = headlineLargeEmphasized.bookText(),
      headlineMediumEmphasized = headlineMediumEmphasized.bookText(),
      headlineSmallEmphasized = headlineSmallEmphasized.bookText(),
      titleLargeEmphasized = titleLargeEmphasized.bookText(),
    )
  }
}

/** The reader's body style: serif, generous line height, sized for long sessions. */
val ReaderTextStyle: TextStyle by lazy { TextStyle(fontSize = 20.sp, lineHeight = 32.sp).bookText() }

fun themeColorScheme(scheme: ThemeColorScheme, dark: Boolean, pureBlack: Boolean = false): ColorScheme {
  val colors = dynamicColorScheme(
    primary = seedColor(scheme),
    isDark = dark,
    style = PaletteStyle.TonalSpot,
    specVersion = ColorSpec.SpecVersion.SPEC_2025,
  )
  return if (dark && pureBlack) colors.pureBlack() else colors
}

@Composable
fun rememberThemeColorScheme(scheme: ThemeColorScheme, dark: Boolean, pureBlack: Boolean = false): ColorScheme =
  remember(scheme, dark, pureBlack) { themeColorScheme(scheme, dark, pureBlack) }

/** Changing the scheme or the mode blends the colours over rather than swapping in a frame. */
@Composable
fun AloudTheme(
  themeMode: ThemeMode,
  colorScheme: ThemeColorScheme,
  pureBlack: Boolean,
  systemDark: Boolean,
  content: @Composable () -> Unit,
) {
  val dark = when (themeMode) {
    ThemeMode.FollowSystem -> systemDark
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
  }
  MaterialExpressiveTheme(
    colorScheme = animateColorScheme(rememberThemeColorScheme(colorScheme, dark, pureBlack)),
    typography = AloudTypography,
    content = content,
  )
}

/** Only the backdrop goes black; cards and bars keep their tones. */
private fun ColorScheme.pureBlack(): ColorScheme = copy(
  background = Color.Black,
  surface = Color.Black,
  surfaceDim = Color.Black,
  surfaceContainerLowest = Color.Black,
)
