package com.akhouri.aloud.ui

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

/** Windows' own open dialogs. Each blocks until the user chooses or cancels. */
object FilePickers {

  /** Books, several at once. */
  fun openBooks(parent: Frame?, extensions: List<String>): List<File> {
    val dialog = FileDialog(parent, "Add books", FileDialog.LOAD).apply {
      isMultipleMode = true
      // On Windows the file name box doubles as the filter.
      file = extensions.joinToString(";") { "*.$it" }
      setFilenameFilter { _, name -> extensions.any { name.endsWith(".$it", ignoreCase = true) } }
    }
    dialog.isVisible = true
    return dialog.files?.toList().orEmpty()
  }

  /** A folder; every readable book inside it, and in folders inside it, is added. */
  fun openFolder(parent: Frame?): File? {
    val chooser = JFileChooser().apply {
      dialogTitle = "Add a folder of books"
      fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
      isAcceptAllFileFilterUsed = false
    }
    return if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
  }

  fun openImage(parent: Frame?): File? = openOne(parent, "Choose a cover", listOf("jpg", "jpeg", "png", "gif", "bmp"))

  fun openVoice(parent: Frame?): File? = openOne(parent, "Add a Piper voice", listOf("onnx"))

  private fun openOne(parent: Frame?, title: String, extensions: List<String>): File? {
    val dialog = FileDialog(parent, title, FileDialog.LOAD).apply {
      file = extensions.joinToString(";") { "*.$it" }
      setFilenameFilter { _, name -> extensions.any { name.endsWith(".$it", ignoreCase = true) } }
    }
    dialog.isVisible = true
    return dialog.files?.firstOrNull()
  }
}
