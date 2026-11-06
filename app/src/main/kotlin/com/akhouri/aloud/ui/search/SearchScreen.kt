package com.akhouri.aloud.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.akhouri.aloud.data.FtsQuery
import com.akhouri.aloud.data.SearchResult
import com.akhouri.aloud.data.entity.SearchHistoryEntity
import com.akhouri.aloud.graph
import com.akhouri.aloud.ui.EmptyState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
  bookId: String,
  onBack: () -> Unit,
  onJumpTo: () -> Unit,
) {
  val context = LocalContext.current
  val appGraph = context.graph
  val scope = rememberCoroutineScope()

  var query by remember { mutableStateOf("") }
  var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
  var searching by remember { mutableStateOf(false) }
  var searched by remember { mutableStateOf(false) }

  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }

  // Debounced so a 900-page index is not queried on every keystroke.
  LaunchedEffect(query) {
    val match = FtsQuery.sanitise(query)
    if (match == null || query.length < 2) {
      results = emptyList()
      searched = false
      return@LaunchedEffect
    }
    searching = true
    delay(250)
    results = runCatching { appGraph.database.paragraphDao().searchInBook(bookId, match) }
      .getOrDefault(emptyList())
    searching = false
    searched = true
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Search") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
  ) { padding ->
    Column(Modifier.padding(padding)) {
      OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("Find a word or phrase") },
        singleLine = true,
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp)
          .focusRequester(focus),
      )

      if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())

      when {
        results.isNotEmpty() -> {
          Text(
            text = "${results.size} result${if (results.size == 1) "" else "s"}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
          )
          LazyColumn {
            items(results, key = { it.paragraphIndex }) { result ->
              ResultRow(result) {
                scope.launch {
                  appGraph.database.searchHistoryDao().record(
                    SearchHistoryEntity(query, bookId, System.currentTimeMillis()),
                  )
                  appGraph.narrator.open(bookId, result.paragraphIndex)
                  onJumpTo()
                }
              }
              HorizontalDivider()
            }
          }
        }
        searched -> EmptyState(
          title = "Nothing found",
          subtitle = "No passage in this book matches “$query”.",
        )
        else -> EmptyState(
          title = "Search the whole book",
          subtitle = "Every word is indexed, including parts you have never listened to. " +
            "Tap a result to start reading from there.",
        )
      }
    }
  }
}

@Composable
private fun ResultRow(result: SearchResult, onClick: () -> Unit) {
  Column(
    Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 12.dp),
  ) {
    Text(
      text = buildAnnotatedString {
        // The snippet comes back with matches wrapped in square brackets by SQLite.
        var rest = result.snippet
        while (true) {
          val open = rest.indexOf('[')
          val close = if (open >= 0) rest.indexOf(']', open) else -1
          if (open < 0 || close < 0) {
            append(rest)
            break
          }
          append(rest.substring(0, open))
          withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(rest.substring(open + 1, close))
          }
          rest = rest.substring(close + 1)
        }
      },
      style = MaterialTheme.typography.bodyMedium,
    )
    Text(
      text = buildString {
        append(result.chapterTitle ?: "Chapter ${result.chapterIndex + 1}")
        result.page?.let { append(" · page $it") }
      },
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.padding(top = 4.dp),
    )
  }
}
