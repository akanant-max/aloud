package com.akhouri.aloud.importer

import com.akhouri.aloud.data.BookDao
import com.akhouri.aloud.util.CrashReporter
import java.io.File
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
 * Imports files one at a time, independently of any screen. Each file is its own unit:
 * whatever happens to one, the next still gets its turn.
 */
class ImportQueue(
  private val importer: BookImporter,
  private val books: BookDao,
  private val scope: CoroutineScope,
) {

  private class Request(val file: File, val collectionId: Long?)

  private val requests = Channel<Request>(Channel.UNLIMITED)

  private val _waiting = MutableStateFlow(0)
  val waiting: StateFlow<Int> = _waiting.asStateFlow()

  private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 64)

  /** One line for each file that could not be imported, and a summary for a batch. */
  val messages: SharedFlow<String> = _messages.asSharedFlow()

  private var batchAdded = 0

  init {
    scope.launch {
      for (request in requests) {
        val failure = runOne(request)
        if (failure != null) _messages.tryEmit(failure) else batchAdded++
        _waiting.update { (it - 1).coerceAtLeast(0) }
        if (_waiting.value == 0) {
          if (batchAdded > 1) _messages.tryEmit("Added $batchAdded books")
          batchAdded = 0
        }
      }
    }
  }

  /** Queues files, or every readable book inside a folder; [collectionId] files them there. */
  fun enqueue(files: List<File>, collectionId: Long? = null) {
    val expanded = files.flatMap { file ->
      if (file.isDirectory) {
        file.walkTopDown().maxDepth(4).filter { it.isFile && importer.isSupported(it.name) }.toList()
      } else {
        listOf(file)
      }
    }.distinctBy { it.absolutePath.lowercase() }

    if (expanded.isEmpty()) {
      if (files.isNotEmpty()) _messages.tryEmit("No books Aloud can read were found there")
      return
    }
    _waiting.update { it + expanded.size }
    expanded.forEach { requests.trySend(Request(it, collectionId)) }
  }

  private suspend fun runOne(request: Request): String? = try {
    val result = importer.import(request.file)
    val bookId = result.getOrNull()
    if (bookId == null) {
      result.exceptionOrNull()?.message ?: "One file could not be imported"
    } else {
      request.collectionId?.let { collectionId ->
        val last = books.inCollection(collectionId).maxOfOrNull { it.collectionPosition } ?: -1
        books.setCollection(bookId, collectionId, last + 1)
      }
      null
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Throwable) {
    CrashReporter.record(e, "importing ${request.file.name}")
    "${request.file.name} could not be imported (${e.javaClass.simpleName})"
  }
}
