@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.akhouri.aloud.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.akhouri.aloud.R
import com.akhouri.aloud.data.ThemeColorScheme
import com.akhouri.aloud.data.ThemeMode
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

/** Dynamic colour comes from the wallpaper, which Android only offers from version 12. */
val dynamicColorAvailable: Boolean get() = Build.VERSION.SDK_INT >= 31

fun seedColor(scheme: ThemeColorScheme): Color = when (scheme) {
  ThemeColorScheme.Blue, ThemeColorScheme.Dynamic -> AloudBlue
  ThemeColorScheme.Lagoon -> Color(0xFF00696E)
  ThemeColorScheme.Forest -> Color(0xFF3B6D2A)
  ThemeColorScheme.Sunset -> Color(0xFFC4501F)
  ThemeColorScheme.Berry -> Color(0xFFA3305F)
  ThemeColorScheme.Lavender -> Color(0xFF6B4EB8)
}

/** For the words that come from the book: its title, author, chapters and text. */
fun TextStyle.bookText(): TextStyle = copy(
  fontFamily = FontFamily(
    listOf(400, 500, 600, 700).map { weight ->
      Font(
        resId = R.font.literata,
        weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(
          FontVariation.weight(weight),
          FontVariation.opticalSizing(fontSize),
        ),
      )
    },
  ),
)

private val AloudTypography: Typography = Typography().run {
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

/** The reader's body style: serif, generous line height, sized for long sessions. */
val ReaderTextStyle = TextStyle(fontSize = 19.sp, lineHeight = 31.sp).bookText()

/** The colours a scheme produces, light or dark, outside of composition. */
fun themeColorScheme(
  context: Context,
  scheme: ThemeColorScheme,
  dark: Boolean,
  pureBlack: Boolean = false,
): ColorScheme {
  val colors = if (scheme == ThemeColorScheme.Dynamic && dynamicColorAvailable) {
    if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
  } else {
    dynamicColorScheme(
      // The seed as primary keeps its full saturation; secondary and tertiary are derived
      // the way Android derives them from a wallpaper.
      primary = seedColor(scheme),
      isDark = dark,
      style = PaletteStyle.TonalSpot,
      specVersion = ColorSpec.SpecVersion.SPEC_2025,
    )
  }
  return if (dark && pureBlack) colors.pureBlack() else colors
}

@Composable
fun rememberThemeColorScheme(
  scheme: ThemeColorScheme,
  dark: Boolean,
  pureBlack: Boolean = false,
): ColorScheme {
  val context = LocalContext.current
  return remember(context, scheme, dark, pureBlack) {
    themeColorScheme(context, scheme, dark, pureBlack)
  }
}

/** Changing the scheme or the mode blends the colours over rather than swapping in a frame. */
@Composable
fun AloudTheme(
  themeMode: ThemeMode = ThemeMode.FollowSystem,
  colorScheme: ThemeColorScheme = ThemeColorScheme.Blue,
  pureBlack: Boolean = false,
  content: @Composable () -> Unit,
) {
  val dark = when (themeMode) {
    ThemeMode.FollowSystem -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
  }
  MaterialExpressiveTheme(
    colorScheme = animateColorScheme(rememberThemeColorScheme(colorScheme, dark, pureBlack)),
    typography = AloudTypography,
    content = content,
  )
}

/**
 * Only the backdrop goes black. Cards, sheets and bars keep their tones so the layout does not
 * dissolve into one flat surface on an OLED screen.
 */
private fun ColorScheme.pureBlack(): ColorScheme = copy(
  background = Color.Black,
  surface = Color.Black,
  surfaceDim = Color.Black,
  surfaceContainerLowest = Color.Black,
)
