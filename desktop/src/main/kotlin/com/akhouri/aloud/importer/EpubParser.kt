package com.akhouri.aloud.importer

import com.akhouri.aloud.data.BookFormat
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * EPUB 2 and 3. An EPUB is a ZIP of XHTML plus an XML package document; jsoup reads both.
 * Elements are matched on their local name, because real EPUBs use whatever namespace prefixes
 * they like.
 */
class EpubParser : BookParser {

  override val format = BookFormat.EPUB

  override fun supports(fileName: String): Boolean = fileName.endsWith(".epub", ignoreCase = true)

  override suspend fun parse(file: File, sink: ImportSink) {
    withContext(Dispatchers.IO) {
      try {
        ZipFile(file).use { zip -> readBook(zip, sink) }
      } catch (e: ImportException) {
        throw e
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        throw ImportException("This EPUB could not be opened. It may be damaged or DRM-protected.", e)
      }
    }
  }

  private suspend fun readBook(zip: ZipFile, sink: ImportSink) {
    val opfPath = xml(zip, "META-INF/container.xml")
      ?.named("rootfile")?.firstOrNull()?.attr("full-path")?.takeIf { it.isNotBlank() }
      ?: throw ImportException("Not a valid EPUB: no package file")
    val opfDir = opfPath.substringBeforeLast('/', missingDelimiterValue = "")
    val opf = parseOpf(zip, opfPath)

    val coverBytes = opf.coverHref
      ?.let { resolve(opfDir, it) }
      ?.let { path -> zip.getEntry(path)?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } } }

    sink.metadata(title = opf.title, author = opf.author, language = opf.language, cover = coverBytes)

    val tocTitles = readTocTitles(zip, opfDir, opf)
    var emittedAnything = false

    for (href in opf.spineHrefs) {
      coroutineContext.ensureActive()
      val path = resolve(opfDir, href)
      val entry = zip.getEntry(path)
      if (entry == null) {
        sink.warn("Missing chapter file: $href")
        continue
      }
      val html = try {
        zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
      } catch (e: Exception) {
        sink.warn("Could not read chapter: $href")
        continue
      }
      val paragraphs = HtmlText.toParagraphs(html)
      if (paragraphs.isEmpty()) continue

      val title = tocTitles[path] ?: tocTitles[href] ?: HtmlText.firstHeading(html)
      emitChapter(sink, title, paragraphs)
      emittedAnything = true
    }

    if (!emittedAnything) throw ImportException("No readable text was found in this EPUB")
  }

  private class Opf(
    val title: String?,
    val author: String?,
    val language: String?,
    val coverHref: String?,
    val spineHrefs: List<String>,
    val navHref: String?,
    val ncxHref: String?,
  )

  private fun parseOpf(zip: ZipFile, opfPath: String): Opf {
    val doc = xml(zip, opfPath) ?: throw ImportException("Not a valid EPUB: package file missing")

    val title = doc.named("title").firstNotNullOfOrNull { it.text().trim().takeIf(String::isNotEmpty) }
    val author = doc.named("creator").firstNotNullOfOrNull { it.text().trim().takeIf(String::isNotEmpty) }
    val language = doc.named("language").firstNotNullOfOrNull { it.text().trim().takeIf(String::isNotEmpty) }

    val manifest = LinkedHashMap<String, String>()
    val media = HashMap<String, String>()
    var coverHref: String? = null
    var navHref: String? = null
    for (item in doc.named("item")) {
      val id = item.attr("id")
      val href = item.attr("href")
      if (id.isEmpty() || href.isEmpty()) continue
      manifest[id] = href
      media[id] = item.attr("media-type")
      val properties = item.attr("properties")
      if (properties.contains("cover-image")) coverHref = href
      if (properties.split(' ').contains("nav")) navHref = href
    }

    if (coverHref == null) {
      val coverMetaId = doc.named("meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")
      coverHref = coverMetaId?.let { manifest[it] }
        ?: manifest.entries.firstOrNull { (id, href) ->
          media[id]?.startsWith("image/") == true &&
            (id.contains("cover", true) || href.contains("cover", true))
        }?.value
        // Failing that, the first image the book declares is usually its cover.
        ?: manifest.entries.firstOrNull { (id, _) -> media[id]?.startsWith("image/") == true }?.value
    }

    val spine = doc.named("spine").firstOrNull()
    val ncxHref = spine?.attr("toc")?.takeIf { it.isNotEmpty() }?.let { manifest[it] }
      ?: manifest.entries.firstOrNull { media[it.key] == "application/x-dtbncx+xml" }?.value

    val spineHrefs = doc.named("itemref")
      .mapNotNull { manifest[it.attr("idref")] }
      // The navigation document is sometimes in the spine; it is furniture, not text.
      .filter { it != navHref }

    if (spineHrefs.isEmpty()) throw ImportException("Not a valid EPUB: the spine is empty")
    return Opf(title, author, language, coverHref, spineHrefs, navHref, ncxHref)
  }

  /** Chapter titles keyed by the resolved path of the file they point at. */
  private fun readTocTitles(zip: ZipFile, opfDir: String, opf: Opf): Map<String, String> {
    val titles = HashMap<String, String>()

    opf.ncxHref?.let { href ->
      runCatching {
        val ncx = xml(zip, resolve(opfDir, href)) ?: return@runCatching
        for (point in ncx.named("navpoint")) {
          val label = point.children().firstOrNull { it.local() == "navlabel" }?.text()?.trim()
          val src = point.children().firstOrNull { it.local() == "content" }?.attr("src")
          if (!label.isNullOrEmpty() && !src.isNullOrEmpty()) {
            titles.putIfAbsent(resolve(opfDir, src.substringBefore('#')), label)
          }
        }
      }
    }

    opf.navHref?.let { href ->
      runCatching {
        val entry = zip.getEntry(resolve(opfDir, href)) ?: return@runCatching
        val html = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        val navDir = resolve(opfDir, href).substringBeforeLast('/', missingDelimiterValue = "")
        for (link in Jsoup.parse(html).select("a[href]")) {
          val target = link.attr("href").substringBefore('#')
          val label = link.text().trim()
          if (target.isNotEmpty() && label.isNotEmpty()) {
            titles.putIfAbsent(resolve(navDir, target), label)
          }
        }
      }
    }

    return titles
  }

  // ---- helpers ------------------------------------------------------------------------------

  private fun xml(zip: ZipFile, path: String): Document? {
    val entry = zip.getEntry(path) ?: return null
    return zip.getInputStream(entry).use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
  }

  private fun Element.local(): String = tagName().substringAfterLast(':').lowercase()

  private fun Document.named(name: String): List<Element> = allElements.filter { it.local() == name }

  /** Resolves an href against a directory, normalising `..` and percent-escapes. */
  private fun resolve(baseDir: String, href: String): String {
    val decoded = runCatching { URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") }.getOrDefault(href)
    val combined = if (baseDir.isEmpty()) decoded else "$baseDir/$decoded"
    val parts = ArrayDeque<String>()
    for (segment in combined.split('/')) {
      when (segment) {
        "", "." -> Unit
        ".." -> parts.removeLastOrNull()
        else -> parts.addLast(segment)
      }
    }
    return parts.joinToString("/")
  }
}
