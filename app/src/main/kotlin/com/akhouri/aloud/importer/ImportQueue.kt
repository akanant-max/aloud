package com.akhouri.aloud.importer

import android.net.Uri
import com.akhouri.aloud.data.AloudDatabase
import com.akhouri.aloud.util.CrashReporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Imports files one at a time, independently of whatever screen asked for them.
 *
 * A large book can take minutes. Tying that to the library screen meant leaving the screen
 * abandoned the import, and one file going wrong could take the rest of a batch with it. Here
 * each file is its own unit: whatever happens to one, the next still gets its turn.
 */
class ImportQueue(
  private val importer: BookImporter,
  private val database: AloudDatabase,
  private val scope: CoroutineScope,
) {

  private class Request(val uri: Uri, val collectionId: Long?)

  private val requests = Channel<Request>(Channel.UNLIMITED)

  private val _waiting = MutableStateFlow(0)

  /** Files queued or in progress. */
  val waiting: StateFlow<Int> = _waiting.asStateFlow()

  private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 32)

  /** One line for each file that could not be imported, and a summary for a batch. */
  val messages: SharedFlow<String> = _messages.asSharedFlow()

  init {
    scope.launch {
      for (request in requests) {
        val failure = runOne(request)
        if (failure != null) _messages.tryEmit(failure)
        _waiting.update { (it - 1).coerceAtLeast(0) }
      }
    }
  }

  /** Adds files to the queue; [collectionId] files each one there once it has imported. */
  fun enqueue(uris: List<Uri>, collectionId: Long? = null) {
    if (uris.isEmpty()) return
    _waiting.update { it + uris.size }
    uris.forEach { requests.trySend(Request(it, collectionId)) }
  }

  /** Returns what to tell the user if this file failed, or null if it went in. */
  private suspend fun runOne(request: Request): String? {
    return try {
      val result = importer.import(request.uri)
      val bookId = result.getOrNull()
      if (bookId == null) {
        result.exceptionOrNull()?.message ?: "One file could not be imported"
      } else {
        request.collectionId?.let { collectionId ->
          val last = database.bookDao().inCollection(collectionId)
            .maxOfOrNull { it.collectionPosition } ?: -1
          database.bookDao().setCollection(bookId, collectionId, last + 1)
        }
        null
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      // The importer handles its own failures; this is the net under it, so that nothing a
      // single file does can stop the files behind it.
      CrashReporter.record(e, "importing ${request.uri}")
      "One file could not be imported (${e.javaClass.simpleName})"
    }
  }
}
