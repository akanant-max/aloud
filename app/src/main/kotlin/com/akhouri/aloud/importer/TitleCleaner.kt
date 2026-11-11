package com.akhouri.aloud.importer

/**
 * Turns a download-site file name into something that reads like a title.
 *
 * Files rarely arrive with clean names: hash prefixes, the site's own name, underscores for
 * spaces. The cleaned result is only ever a starting point — the title can be edited by hand.
 */
object TitleCleaner {

  private val EXTENSION = Regex("\\.(epub|pdf|txt)$", RegexOption.IGNORE_CASE)
  private val HASH_PREFIX = Regex("^[0-9a-fA-F]{6,}[-_ ]+")
  private val SITE_TAGS = Regex(
    "(_?OceanofPDF\\.com_?|\\(?z-lib(rary)?(\\.org)?\\)?|\\(?libgen(\\.\\w+)?\\)?|" +
      "--? ?Anna.?s Archive|\\(?pdfdrive(\\.com)?\\)?|www\\.\\S+\\.(com|org|net))",
    RegexOption.IGNORE_CASE,
  )
  private val SEPARATORS = Regex("[_]+")
  private val SPACES = Regex("\\s{2,}")

  fun fromFileName(fileName: String): String =
    clean(fileName.replace(EXTENSION, "")).ifBlank { "Untitled" }

  fun clean(raw: String): String =
    raw
      .replace(SITE_TAGS, " ")
      .replace(HASH_PREFIX, "")
      .replace(SEPARATORS, " ")
      .replace(SPACES, " ")
      .trim(' ', '-', '.', '_')
}
