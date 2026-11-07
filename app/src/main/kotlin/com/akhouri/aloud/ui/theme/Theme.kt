package com.akhouri.aloud.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.akhouri.aloud.R
import com.akhouri.aloud.data.ThemeMode
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme

/** The deep blue the whole palette is derived from. */
val AloudBlue = Color(0xFF003B7F)

/**
 * Literata, for words that come from the book itself — titles, chapters, and the reader's body
 * text. A serif reads better over long stretches than the interface font does.
 */
val BookFamily = FontFamily(
  Font(R.font.literata, FontWeight.Normal),
  Font(R.font.literata, FontWeight.Medium),
  Font(R.font.literata, FontWeight.SemiBold),
  Font(R.font.literata, FontWeight.Bold),
)

private val AloudTypography: Typography = Typography().run {
  copy(
    displayLarge = displayLarge.copy(fontFamily = BookFamily),
    displayMedium = displayMedium.copy(fontFamily = BookFamily),
    displaySmall = displaySmall.copy(fontFamily = BookFamily),
    headlineLarge = headlineLarge.copy(fontFamily = BookFamily),
    headlineMedium = headlineMedium.copy(fontFamily = BookFamily),
    headlineSmall = headlineSmall.copy(fontFamily = BookFamily),
    titleLarge = titleLarge.copy(fontFamily = BookFamily),
  )
}

/** The reader's body style: serif, generous line height, sized for long sessions. */
val ReaderTextStyle = TextStyle(
  fontFamily = BookFamily,
  fontSize = 19.sp,
  lineHeight = 31.sp,
)

@Composable
fun AloudTheme(
  themeMode: ThemeMode = ThemeMode.FollowSystem,
  pureBlack: Boolean = false,
  content: @Composable () -> Unit,
) {
  val dark = when (themeMode) {
    ThemeMode.FollowSystem -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
  }

  val context = LocalContext.current
  val colors = remember(context, dark, pureBlack) {
    val base = if (Build.VERSION.SDK_INT >= 31) {
      if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
      dynamicColorScheme(
        // The seed as primary keeps its full saturation; secondary and tertiary are derived
        // the way Android derives them from a wallpaper.
        primary = AloudBlue,
        isDark = dark,
        style = PaletteStyle.TonalSpot,
      )
    }
    if (dark && pureBlack) base.pureBlack() else base
  }

  MaterialTheme(
    colorScheme = colors,
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
