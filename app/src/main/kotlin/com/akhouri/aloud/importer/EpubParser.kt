package com.akhouri.aloud.importer

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.akhouri.aloud.data.entity.BookFormat
import java.net.URLDecoder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import kotlin.coroutines.coroutineContext

/**
 * EPUB 2 and 3, parsed without an external library: an EPUB is a ZIP of XHTML plus an XML
 * manifest, and Android ships both a ZIP reader and a pull parser.
 *
 * The parser is namespace-unaware on purpose. Real EPUBs use whatever prefixes they like, so
 * matching on the local name is more reliable than trusting declared namespaces.
 */
class EpubParser : BookParser {

  override val format = BookFormat.EPUB

  override fun supports(mimeType: String?, fileName: String): Boolean =
    mimeType == "application/epub+zip" || fileName.endsWith(".epub", ignoreCase = true)

  override suspend fun parse(context: Context, uri: Uri, sink: ImportSink) {
    withContext(Dispatchers.IO) {
      val cached = copyToCache(context, uri, "epub")
      try {
        ZipFile(cached).use { zip -> readBook(zip, sink) }
      } catch (e: ImportException) {
        throw e
      } catch (e: Exception) {
        throw ImportException("This EPUB could not be opened. It may be damaged or DRM-protected.", e)
      } finally {
        cached.delete()
      }
    }
  }

  private suspend fun readBook(zip: ZipFile, sink: ImportSink) {
    val opfPath = findOpfPath(zip)
      ?: throw ImportException("Not a valid EPUB: no package file")
    val opfDir = opfPath.substringBeforeLast('/', missingDelimiterValue = "")
    val opf = parseOpf(zip, opfPath)

    val coverBytes = opf.coverHref
      ?.let { resolve(opfDir, it) }
      ?.let { path -> zip.getEntry(path)?.let { zip.getInputStream(it).use { s -> s.readBytes() } } }

    sink.metadata(
      title = opf.title,
      author = opf.author,
      language = opf.language,
      cover = coverBytes,
    )

    val tocTitles = readTocTitles(zip, opfDir, opf)
    var emittedAnything = false

    for (href in opf.spineHrefs) {
      coroutineContext.ensureActive()
      val path = resolve(opfDir, href)
      val entry: ZipEntry? = zip.getEntry(path)
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

      val title = tocTitles[path]
        ?: tocTitles[href]
        ?: HtmlText.firstHeading(html)

      sink.startChapter(title)
      paragraphs.forEach { sink.paragraph(it) }
      emittedAnything = true
    }

    if (!emittedAnything) {
      throw ImportException("No readable text was found in this EPUB")
    }
  }

  // ---- package document -------------------------------------------------------------------

  private data class Opf(
    val title: String?,
    val author: String?,
    val language: String?,
    val coverHref: String?,
    val spineHrefs: List<String>,
    val navHref: String?,
    val ncxHref: String?,
  )

  private fun findOpfPath(zip: ZipFile): String? {
    val container = zip.getEntry("META-INF/container.xml") ?: return null
    zip.getInputStream(container).use { stream ->
      val parser = newParser(stream)
      while (parser.next() != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType == XmlPullParser.START_TAG && parser.localName() == "rootfile") {
          parser.getAttributeValue(null, "full-path")?.let { return it }
        }
      }
    }
    return null
  }

  private fun parseOpf(zip: ZipFile, opfPath: String): Opf {
    val entry = zip.getEntry(opfPath) ?: throw ImportException("Not a valid EPUB: package file missing")

    var title: String? = null
    var author: String? = null
    var language: String? = null
    var coverMetaId: String? = null
    var coverHref: String? = null
    var navHref: String? = null
    var ncxId: String? = null

    // id -> href, plus the hrefs of items flagged as cover image or navigation document.
    val manifest = HashMap<String, String>()
    val manifestMedia = HashMap<String, String>()
    val spineIds = ArrayList<String>()

    zip.getInputStream(entry).use { stream ->
      val parser = newParser(stream)
      var current: String? = null
      val text = StringBuilder()

      while (parser.next() != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
          XmlPullParser.START_TAG -> {
            current = parser.localName()
            text.setLength(0)
            when (current) {
              "item" -> {
                val id = parser.getAttributeValue(null, "id")
                val href = parser.getAttributeValue(null, "href")
                val properties = parser.getAttributeValue(null, "properties").orEmpty()
                val media = parser.getAttributeValue(null, "media-type").orEmpty()
                if (id != null && href != null) {
                  manifest[id] = href
                  manifestMedia[id] = media
                  if (properties.contains("cover-image")) coverHref = href
                  if (properties.split(' ').contains("nav")) navHref = href
                }
              }
              "itemref" -> parser.getAttributeValue(null, "idref")?.let(spineIds::add)
              "spine" -> ncxId = parser.getAttributeValue(null, "toc")
              "meta" -> {
                if (parser.getAttributeValue(null, "name") == "cover") {
                  coverMetaId = parser.getAttributeValue(null, "content")
                }
              }
            }
          }
          XmlPullParser.TEXT -> text.append(parser.text)
          XmlPullParser.END_TAG -> {
            val value = text.toString().trim()
            if (value.isNotEmpty()) {
              when (parser.localName()) {
                "title" -> if (title == null) title = value
                "creator" -> if (author == null) author = value
                "language" -> if (language == null) language = value
              }
            }
            text.setLength(0)
            current = null
          }
        }
      }
    }

    if (coverHref == null) {
      coverHref = coverMetaId?.let { manifest[it] }
        // Last resort: an image whose id or file name says cover.
        ?: manifest.entries.firstOrNull { (id, href) ->
          manifestMedia[id]?.startsWith("image/") == true &&
            (id.contains("cover", true) || href.contains("cover", true))
        }?.value
    }

    val ncxHref = ncxId?.let { manifest[it] }
      ?: manifest.entries.firstOrNull { manifestMedia[it.key] == "application/x-dtbncx+xml" }?.value

    val spineHrefs = spineIds.mapNotNull { manifest[it] }
      // The navigation document is part of the spine in some EPUB 3 files; it is furniture.
      .filter { it != navHref }

    if (spineHrefs.isEmpty()) throw ImportException("Not a valid EPUB: the spine is empty")

    return Opf(title, author, language, coverHref, spineHrefs, navHref, ncxHref)
  }

  // ---- table of contents ------------------------------------------------------------------

  /** Chapter titles keyed by the resolved path of the file they point at. */
  private fun readTocTitles(zip: ZipFile, opfDir: String, opf: Opf): Map<String, String> {
    val titles = HashMap<String, String>()

    opf.ncxHref?.let { href ->
      zip.getEntry(resolve(opfDir, href))?.let { entry ->
        runCatching {
          zip.getInputStream(entry).use { stream ->
            val parser = newParser(stream)
            var label: String? = null
            val text = StringBuilder()
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
              when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                  if (parser.localName() == "text") text.setLength(0)
                  if (parser.localName() == "content") {
                    val src = parser.getAttributeValue(null, "src")
                    if (src != null && label != null) {
                      titles.putIfAbsent(resolve(opfDir, src.substringBefore('#')), label!!)
                    }
                  }
                }
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG ->
                  if (parser.localName() == "text") {
                    label = text.toString().trim().takeIf { it.isNotEmpty() }
                  }
              }
            }
          }
        }
      }
    }

    opf.navHref?.let { href ->
      zip.getEntry(resolve(opfDir, href))?.let { entry ->
        runCatching {
          val html = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
          NAV_LINK.findAll(html).forEach { match ->
            val target = match.groupValues[1].substringBefore('#')
            @Suppress("DEPRECATION")
            val label = android.text.Html
              .fromHtml(match.groupValues[2], android.text.Html.FROM_HTML_MODE_COMPACT)
              .toString()
              .trim()
            if (target.isNotEmpty() && label.isNotEmpty()) {
              titles.putIfAbsent(resolve(opfDir, target), label)
            }
          }
        }
      }
    }

    return titles
  }

  // ---- helpers ----------------------------------------------------------------------------

  private fun newParser(stream: java.io.InputStream): XmlPullParser = Xml.newPullParser().apply {
    setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    setInput(stream, null)
  }

  /** The tag name with any namespace prefix dropped. */
  private fun XmlPullParser.localName(): String = name?.substringAfterLast(':').orEmpty()

  /**
   * Resolves an href against the package document's directory, normalising `..` and
   * percent-escapes so the result matches a ZIP entry name.
   */
  private fun resolve(baseDir: String, href: String): String {
    val decoded = runCatching { URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
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

  private companion object {
    val NAV_LINK = Regex(
      "<a\\b[^>]*href=[\"']([^\"'#]+)[^\"']*[\"'][^>]*>(.*?)</a\\s*>",
      setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
  }
}
