package com.akhouri.aloud.importer

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** The file name a content URI reports, or null if the provider does not supply one. */
fun displayName(context: Context, uri: Uri): String? {
  if (uri.scheme == "file") return uri.lastPathSegment

  val cursor: Cursor? = context.contentResolver.query(
    uri,
    arrayOf(OpenableColumns.DISPLAY_NAME),
    null,
    null,
    null,
  )
  cursor?.use {
    val column = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
    if (column >= 0 && it.moveToFirst()) {
      val name = it.getString(column)
      if (!name.isNullOrBlank()) return name
    }
  }
  return uri.lastPathSegment
}

/**
 * Copies a content URI into a private cache file.
 *
 * Needed by formats that require random access. A ZIP central directory lives at the end of the
 * archive, so an EPUB cannot be read from a forward-only content stream.
 */
fun copyToCache(context: Context, uri: Uri, prefix: String): File {
  val target = File.createTempFile(prefix, null, context.cacheDir)
  context.contentResolver.openInputStream(uri)
    ?.use { input -> target.outputStream().use(input::copyTo) }
    ?: run {
      target.delete()
      throw ImportException("Could not open the file")
    }
  return target
}
