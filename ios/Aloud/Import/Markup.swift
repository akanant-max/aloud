import Foundation

/// A forgiving reader for the HTML and XML found in ebooks, which is often malformed. It does not
/// build a tree: it walks tags and text, which is all turning a book into paragraphs needs.
enum Markup {

  struct Tag {
    /// Lower-case local name, without any namespace prefix.
    var name: String
    var closing: Bool
    var selfClosing: Bool
    var attributes: [String: String]
  }

  enum Token {
    case tag(Tag)
    case text(String)
  }

  /// Walks markup, calling back for each tag and run of text, with entities decoded.
  static func scan(_ source: String, _ visit: (Token) -> Void) {
    let chars = Array(source.utf16)
    let count = chars.count
    var i = 0
    var textStart = 0

    func flushText(_ end: Int) {
      if end > textStart {
        let raw = String(utf16CodeUnits: Array(chars[textStart..<end]), count: end - textStart)
        visit(.text(decodeEntities(raw)))
      }
    }

    while i < count {
      guard chars[i] == 60 /* < */ else { i += 1; continue }
      flushText(i)
      // Comments, CDATA and declarations.
      if starts(chars, i, "<!--") {
        i = find(chars, i + 4, "-->").map { $0 + 3 } ?? count
        textStart = i
        continue
      }
      if starts(chars, i, "<![CDATA[") {
        let end = find(chars, i + 9, "]]>") ?? count
        let raw = String(utf16CodeUnits: Array(chars[(i + 9)..<end]), count: end - i - 9)
        visit(.text(raw))
        i = min(count, end + 3)
        textStart = i
        continue
      }
      if i + 1 < count, chars[i + 1] == 33 /* ! */ || chars[i + 1] == 63 /* ? */ {
        i = find(chars, i, ">").map { $0 + 1 } ?? count
        textStart = i
        continue
      }
      // An ordinary tag: find its end, respecting quoted attribute values.
      var j = i + 1
      var quote: UInt16 = 0
      while j < count {
        let c = chars[j]
        if quote != 0 {
          if c == quote { quote = 0 }
        } else if c == 34 || c == 39 {
          quote = c
        } else if c == 62 /* > */ {
          break
        }
        j += 1
      }
      let inner = String(utf16CodeUnits: Array(chars[(i + 1)..<min(j, count)]), count: min(j, count) - i - 1)
      let tag = parseTag(inner)
      if let tag { visit(.tag(tag)) }
      i = min(count, j + 1)
      textStart = i
      // Skip the contents of script and style.
      if let tag, !tag.closing, !tag.selfClosing, tag.name == "script" || tag.name == "style" {
        if let end = findCaseInsensitive(chars, i, "</\(tag.name)") {
          i = end
          textStart = i
        }
      }
    }
    flushText(count)
  }

  private static func parseTag(_ inner: String) -> Tag? {
    var body = inner.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !body.isEmpty else { return nil }
    var closing = false
    if body.hasPrefix("/") { closing = true; body.removeFirst() }
    var selfClosing = false
    if body.hasSuffix("/") { selfClosing = true; body.removeLast() }
    let nameEnd = body.firstIndex(where: { $0.isWhitespace }) ?? body.endIndex
    var name = String(body[..<nameEnd]).lowercased()
    if let colon = name.lastIndex(of: ":") { name = String(name[name.index(after: colon)...]) }
    guard !name.isEmpty else { return nil }
    var attributes: [String: String] = [:]
    if !closing {
      let rest = String(body[nameEnd...])
      let pattern = #"([A-Za-z_:][-A-Za-z0-9_:.]*)\s*(?:=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+)))?"#
      if let regex = try? NSRegularExpression(pattern: pattern) {
        let ns = rest as NSString
        for match in regex.matches(in: rest, range: NSRange(location: 0, length: ns.length)) {
          var key = ns.substring(with: match.range(at: 1)).lowercased()
          if let colon = key.lastIndex(of: ":"), !key.hasPrefix("xmlns") { key = String(key[key.index(after: colon)...]) }
          var value = ""
          for group in 2...4 where match.range(at: group).location != NSNotFound {
            value = ns.substring(with: match.range(at: group))
          }
          attributes[key] = decodeEntities(value)
        }
      }
    }
    return Tag(name: name, closing: closing, selfClosing: selfClosing, attributes: attributes)
  }

  private static func starts(_ chars: [UInt16], _ at: Int, _ prefix: String) -> Bool {
    let p = Array(prefix.utf16)
    guard at + p.count <= chars.count else { return false }
    for k in 0..<p.count where chars[at + k] != p[k] { return false }
    return true
  }

  private static func find(_ chars: [UInt16], _ from: Int, _ needle: String) -> Int? {
    let n = Array(needle.utf16)
    guard !n.isEmpty, from < chars.count else { return nil }
    var i = from
    while i + n.count <= chars.count {
      if chars[i] == n[0] {
        var ok = true
        for k in 1..<n.count where chars[i + k] != n[k] { ok = false; break }
        if ok { return i }
      }
      i += 1
    }
    return nil
  }

  private static func findCaseInsensitive(_ chars: [UInt16], _ from: Int, _ needle: String) -> Int? {
    let lower = chars[min(from, chars.count)...].map { c -> UInt16 in (c >= 65 && c <= 90) ? c + 32 : c }
    return find(Array(lower), 0, needle.lowercased()).map { $0 + from }
  }

  // MARK: Entities

  private static let named: [String: String] = [
    "amp": "&", "lt": "<", "gt": ">", "quot": "\"", "apos": "'", "nbsp": " ",
    "mdash": "—", "ndash": "–", "hellip": "…", "lsquo": "‘", "rsquo": "’", "ldquo": "“", "rdquo": "”",
    "laquo": "«", "raquo": "»", "copy": "©", "reg": "®", "trade": "™", "shy": "", "middot": "·",
    "bull": "•", "eacute": "é", "egrave": "è", "aacute": "á", "agrave": "à", "iacute": "í",
    "oacute": "ó", "uacute": "ú", "ntilde": "ñ", "ccedil": "ç", "uuml": "ü", "ouml": "ö", "auml": "ä",
    "szlig": "ß", "deg": "°", "frac12": "½", "frac14": "¼", "frac34": "¾", "times": "×", "thinsp": " ",
    "ensp": " ", "emsp": " ", "zwj": "", "zwnj": "", "prime": "′", "Prime": "″", "sect": "§", "para": "¶",
    "dagger": "†", "Dagger": "‡", "pound": "£", "euro": "€", "yen": "¥", "cent": "¢",
  ]

  static func decodeEntities(_ text: String) -> String {
    guard text.contains("&") else { return text }
    var out = ""
    out.reserveCapacity(text.count)
    var i = text.startIndex
    while i < text.endIndex {
      let c = text[i]
      if c == "&", let semi = text[i...].prefix(12).firstIndex(of: ";") {
        let entity = String(text[text.index(after: i)..<semi])
        var replacement: String?
        if entity.hasPrefix("#x") || entity.hasPrefix("#X") {
          if let code = UInt32(entity.dropFirst(2), radix: 16), let scalar = Unicode.Scalar(code) {
            replacement = String(Character(scalar))
          }
        } else if entity.hasPrefix("#") {
          if let code = UInt32(entity.dropFirst()), let scalar = Unicode.Scalar(code) {
            replacement = String(Character(scalar))
          }
        } else {
          replacement = named[entity]
        }
        if let replacement {
          out += replacement
          i = text.index(after: semi)
          continue
        }
      }
      out.append(c)
      i = text.index(after: i)
    }
    return out
  }

  // MARK: Books

  /// Elements that end a paragraph.
  private static let blocks: Set<String> = [
    "p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6", "tr", "td", "th", "blockquote", "section",
    "article", "dd", "dt", "pre", "figcaption", "caption", "aside", "header", "footer", "v", "stanza",
    "subtitle", "text-author", "epigraph", "cite", "empty-line", "br", "hr", "title", "body", "table", "ul", "ol",
  ]

  /// Elements whose text is never read.
  private static let skipped: Set<String> = ["head", "svg", "template", "noscript", "binary", "math", "rt", "rp"]

  /// Turns a chapter of HTML (or FictionBook XML) into paragraphs.
  static func paragraphs(_ html: String) -> [String] {
    var result: [String] = []
    var current = ""
    var skipDepth = 0

    func endParagraph() {
      let text = TextTools.paragraph(current)
      if !text.isEmpty { result.append(text) }
      current = ""
    }

    scan(html) { token in
      switch token {
      case .tag(let tag):
        if skipped.contains(tag.name) && !tag.selfClosing {
          if tag.closing { skipDepth = max(0, skipDepth - 1) } else { skipDepth += 1 }
          return
        }
        if blocks.contains(tag.name) { endParagraph() }
      case .text(let text):
        if skipDepth == 0 { current += text }
      }
    }
    endParagraph()
    return result
  }

  /// The first heading, used as a chapter's title when the contents list has none.
  static func firstHeading(_ html: String) -> String? {
    var inHeading = false
    var found: String?
    var text = ""
    scan(html) { token in
      guard found == nil else { return }
      switch token {
      case .tag(let tag):
        if ["h1", "h2", "h3", "h4", "h5", "h6"].contains(tag.name) {
          if tag.closing {
            if inHeading {
              let clean = TextTools.paragraph(text)
              if !clean.isEmpty, clean.count <= 120 { found = clean }
              inHeading = false
              text = ""
            }
          } else {
            inHeading = true
          }
        }
      case .text(let t):
        if inHeading { text += t }
      }
    }
    return found
  }

  /// Markup reduced to its text.
  static func plain(_ markup: String) -> String {
    var text = ""
    scan(markup) { token in
      if case .text(let t) = token { text += t }
    }
    return TextTools.paragraph(text)
  }

  /// The text inside the first element with this local name.
  static func firstText(_ xml: String, element: String) -> String? {
    var depth = 0
    var text = ""
    var done = false
    scan(xml) { token in
      guard !done else { return }
      switch token {
      case .tag(let tag):
        guard tag.name == element, !tag.selfClosing else { return }
        if tag.closing {
          if depth > 0 { depth -= 1; if depth == 0 { done = true } }
        } else {
          depth += 1
        }
      case .text(let t):
        if depth > 0 { text += t }
      }
    }
    let clean = TextTools.paragraph(text)
    return clean.isEmpty ? nil : clean
  }

  /// Every tag with this local name.
  static func tags(_ xml: String, named name: String) -> [Tag] {
    var found: [Tag] = []
    scan(xml) { token in
      if case .tag(let tag) = token, tag.name == name, !tag.closing { found.append(tag) }
    }
    return found
  }
}

/// Shared tidying every parser runs its text through.
enum TextTools {
  /// Line breaks inside a paragraph are typesetting, not pauses.
  static func paragraph(_ raw: String) -> String {
    var out = ""
    out.reserveCapacity(raw.count)
    var lastWasSpace = true
    for scalar in raw.unicodeScalars {
      switch scalar {
      case "\u{AD}", "\u{200B}", "\u{FEFF}":
        continue
      case " ", "\t", "\n", "\r", "\u{A0}", "\u{0C}", "\u{2028}", "\u{2029}", "\u{2009}", "\u{202F}":
        if !lastWasSpace { out.unicodeScalars.append(" "); lastWasSpace = true }
      default:
        out.unicodeScalars.append(scalar)
        lastWasSpace = false
      }
    }
    if out.hasSuffix(" ") { out.removeLast() }
    return out
  }

  /// Rejoins a word split across a line break by a hyphen, common in PDF text.
  static func mendHyphenation(_ text: String) -> String {
    text.replacingOccurrences(of: #"(\p{L})-\s+(\p{Ll})"#, with: "$1$2", options: .regularExpression)
  }

  /// Decodes bytes as text: a byte-order mark if there is one, an encoding the file declares,
  /// UTF-8, and finally Windows-1252, which never fails.
  static func decode(_ data: Data) -> String {
    if data.starts(with: [0xEF, 0xBB, 0xBF]) {
      return String(decoding: data.dropFirst(3), as: UTF8.self)
    }
    if data.starts(with: [0xFF, 0xFE]) {
      return String(data: data.dropFirst(2), encoding: .utf16LittleEndian) ?? ""
    }
    if data.starts(with: [0xFE, 0xFF]) {
      return String(data: data.dropFirst(2), encoding: .utf16BigEndian) ?? ""
    }
    let head = String(decoding: data.prefix(1024), as: UTF8.self).lowercased()
    if let range = head.range(of: #"(encoding|charset)\s*=\s*["']?([\w-]+)"#, options: .regularExpression) {
      let declared = head[range]
      if declared.contains("1252") || declared.contains("8859-1") || declared.contains("latin") {
        if let text = String(data: data, encoding: .windowsCP1252) { return text }
      }
      if declared.contains("utf-16") || declared.contains("utf16") {
        if let text = String(data: data, encoding: .utf16) { return text }
      }
    }
    if let text = String(data: data, encoding: .utf8) { return text }
    return String(data: data, encoding: .windowsCP1252) ?? String(decoding: data, as: UTF8.self)
  }
}
