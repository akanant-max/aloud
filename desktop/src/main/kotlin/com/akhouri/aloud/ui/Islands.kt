@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.akhouri.aloud.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon

/*
 * The grouped "island" layout of the settings screen, after Voice by Paul Woitaschek (GPLv3):
 * each group of settings is one softly rounded block in a container colour of its own.
 */

val IslandShape = RoundedCornerShape(32.dp)
val IslandContentPadding = 24.dp

@Composable
fun SettingsIsland(
  title: String,
  containerColor: Color,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = IslandShape,
    color = containerColor,
    contentColor = contentColorFor(containerColor),
  ) {
    Column(Modifier.padding(vertical = 20.dp)) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleLargeEmphasized,
        modifier = Modifier.padding(horizontal = IslandContentPadding).padding(bottom = 12.dp),
      )
      content()
    }
  }
}

@Composable
fun IslandLabel(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelLargeEmphasized,
    modifier = modifier.padding(horizontal = IslandContentPadding).padding(bottom = 12.dp),
  )
}

/** An icon on one of Material's playful shapes, used to lead a settings row. */
@Composable
fun ShapedIcon(
  icon: ImageVector,
  shape: RoundedPolygon,
  containerColor: Color,
  contentColor: Color,
  modifier: Modifier = Modifier,
  size: Dp = 48.dp,
) {
  Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
    Box(Modifier.size(size).background(containerColor, shape.toShape()))
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = contentColor,
      modifier = Modifier.size(size * 0.5f),
    )
  }
}

@Composable
private fun IslandRowLayout(
  title: String,
  modifier: Modifier,
  leading: (@Composable () -> Unit)?,
  summary: String?,
  trailing: (@Composable () -> Unit)?,
) {
  Row(
    modifier = modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    leading?.invoke()
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(text = title, style = MaterialTheme.typography.titleMedium)
      if (summary != null) {
        Text(
          text = summary,
          style = MaterialTheme.typography.bodyMedium,
          color = LocalContentColor.current.copy(alpha = 0.75f),
        )
      }
    }
    trailing?.invoke()
  }
}

/** Its ripple keeps a little distance from the island's edge while the text lines up with the title. */
@Composable
fun IslandRow(
  title: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  summary: String? = null,
  leading: (@Composable () -> Unit)? = null,
  trailing: (@Composable () -> Unit)? = null,
) {
  IslandRowLayout(
    title = title,
    modifier = modifier
      .padding(horizontal = 12.dp)
      .clip(RoundedCornerShape(24.dp))
      .clickable(onClick = onClick),
    leading = leading,
    summary = summary,
    trailing = trailing,
  )
}

/** The whole row is the switch, which is what accessibility services expect. */
@Composable
fun IslandSwitchRow(
  title: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  summary: String? = null,
  leading: (@Composable () -> Unit)? = null,
) {
  IslandRowLayout(
    title = title,
    modifier = modifier
      .padding(horizontal = 12.dp)
      .clip(RoundedCornerShape(24.dp))
      .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    leading = leading,
    summary = summary,
    trailing = {
      Switch(
        checked = checked,
        onCheckedChange = null,
        thumbContent = {
          if (checked) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
          }
        },
      )
    },
  )
}

/** A value nudged up and down in steps, with the current value between the two buttons. */
@Composable
fun IslandStepperRow(
  title: String,
  value: String,
  onDecrease: () -> Unit,
  onIncrease: () -> Unit,
  modifier: Modifier = Modifier,
  summary: String? = null,
  leading: (@Composable () -> Unit)? = null,
) {
  IslandRowLayout(
    title = title,
    modifier = modifier.padding(horizontal = 12.dp),
    leading = leading,
    summary = summary,
    trailing = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = onDecrease) {
          Icon(Icons.Default.Remove, contentDescription = "Less")
        }
        Text(
          text = value,
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.padding(horizontal = 8.dp),
        )
        FilledTonalIconButton(onClick = onIncrease) {
          Icon(Icons.Default.Add, contentDescription = "More")
        }
      }
    },
  )
}
