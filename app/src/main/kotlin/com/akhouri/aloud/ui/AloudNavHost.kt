package com.akhouri.aloud.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.akhouri.aloud.ui.book.BookScreen
import com.akhouri.aloud.ui.library.LibraryScreen
import com.akhouri.aloud.ui.reader.ReaderScreen
import com.akhouri.aloud.ui.search.SearchScreen
import com.akhouri.aloud.ui.settings.SettingsScreen

object Routes {
  const val LIBRARY = "library"
  const val SETTINGS = "settings"
  const val BOOK = "book/{bookId}"
  const val READER = "reader/{bookId}"
  const val SEARCH = "search/{bookId}"

  fun book(bookId: String) = "book/$bookId"

  fun reader(bookId: String) = "reader/$bookId"

  fun search(bookId: String) = "search/$bookId"
}

@Composable
fun AloudNavHost(navController: NavHostController = rememberNavController()) {
  NavHost(navController = navController, startDestination = Routes.LIBRARY) {

    composable(Routes.LIBRARY) {
      LibraryScreen(
        onOpenBook = { navController.navigate(Routes.book(it)) },
        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
      )
    }

    composable(Routes.SETTINGS) {
      SettingsScreen(onBack = { navController.popBackStack() })
    }

    composable(Routes.BOOK) { entry ->
      val bookId = entry.arguments?.getString("bookId").orEmpty()
      BookScreen(
        bookId = bookId,
        onBack = { navController.popBackStack() },
        onRead = { navController.navigate(Routes.reader(bookId)) },
        onSearch = { navController.navigate(Routes.search(bookId)) },
      )
    }

    composable(Routes.READER) { entry ->
      val bookId = entry.arguments?.getString("bookId").orEmpty()
      ReaderScreen(
        bookId = bookId,
        onBack = { navController.popBackStack() },
        onSearch = { navController.navigate(Routes.search(bookId)) },
      )
    }

    composable(Routes.SEARCH) { entry ->
      val bookId = entry.arguments?.getString("bookId").orEmpty()
      SearchScreen(
        bookId = bookId,
        onBack = { navController.popBackStack() },
        onJumpTo = {
          navController.navigate(Routes.reader(bookId)) {
            popUpTo(Routes.BOOK) { inclusive = false }
          }
        },
      )
    }
  }
}
