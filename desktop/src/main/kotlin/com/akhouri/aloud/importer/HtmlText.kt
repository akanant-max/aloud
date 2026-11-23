package com.akhouri.aloud.importer

import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode

/**
 * Turns a chapter of (X)HTML into paragraphs, using jsoup, which survives the malformed markup
 * real ebooks are full of and decodes entities correctly.
 */
object HtmlText {

  private const val MARK = "\u0001"

  /** Elements that end a paragraph. */
  private const val BLOCKS =
    "p,div,li,h1,h2,h3,h4,h5,h6,tr,td,th,blockquote,section,article,dd,dt,pre,figcaption," +
      "caption,aside,header,footer,v,stanza,subtitle,text-author,epigraph,cite,empty-line"

  fun toParagraphs(html: String): List<String> {
    val document = Jsoup.parse(html)
    document.select("script,style,head,svg,template,noscript,binary").remove()
    document.select("br").forEach { it.after(TextNode(MARK)) }
    document.select(BLOCKS).forEach { element ->
      if (element.parent() != null) {
        element.before(TextNode(MARK))
        element.after(TextNode(MARK))
      }
    }
    val text = document.body()?.text() ?: document.text()
    return text.split(MARK).map(TextNormaliser::paragraph).filter { it.isNotEmpty() }
  }

  /** The first heading in a chapter, used as its title when the contents list has none. */
  fun firstHeading(html: String): String? =
    Jsoup.parse(html).selectFirst("h1,h2,h3,h4,h5,h6")?.text()
      ?.let(TextNormaliser::paragraph)
      ?.takeIf { it.isNotEmpty() && it.length <= 120 }

  /** Markup reduced to its text. */
  fun plain(markup: String): String = TextNormaliser.paragraph(Jsoup.parse(markup).text())
}
