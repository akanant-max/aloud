# Aloud

An offline Android app that reads your books out loud using the phone's own
text-to-speech engine.

Built for listening to textbooks: import a book, search the whole thing for a
phrase, tap a result, and narration starts at exactly that passage — with
Bluetooth controls, a lock-screen notification, and playback that keeps going
with the screen off.

## What it reads

| Format | Support |
| --- | --- |
| **EPUB** | EPUB 2 and 3, chapters and cover from the book's own metadata |
| **TXT** | UTF-8 and UTF-16, byte-order marks, streamed so size does not matter |
| **PDF** | Any PDF with a text layer, including ones already run through OCR |

A PDF whose pages are images has no text to read. Aloud says so plainly on
import rather than adding a book that plays silence.

MOBI is not supported, and there is no OCR built in.

## How it works

Every format is normalised at import into one shape — a flat run of paragraphs
grouped into chapters, written into SQLite with an FTS index. After that,
nothing downstream knows or cares where a book came from. The reader, the
search and the narrator all see the same thing.

Narration is chunked at sentence boundaries, never mid-word, with each
utterance carrying a generation stamp. A seek bumps the generation, so
callbacks still in flight from the previous position are recognised as stale
and discarded instead of advancing the position. The saved position is always
the start of the oldest utterance not yet finished, so stopping and resuming
repeats at most one chunk and never skips one.

Speech is exposed to Android through a `SimpleBasePlayer`, so the media
session, the notification and headset buttons all work as they would for any
audio player — while still reporting the real state of the speech engine
rather than pretending to be a file being played.

## Building

The APK builds in CI on every push; download it from the **Actions** tab.

To build locally you need JDK 17 and the Android SDK:

```
./gradlew assembleDebug      # APK at app/build/outputs/apk/debug/
./gradlew testDebugUnitTest
```

## Requirements

- Android 9 (API 28) or newer
- A text-to-speech engine with offline voice data installed. Google
  Text-to-Speech is preferred when present; any engine you select in Android's
  own settings is used otherwise.

## Credits

Interface design, colour palette and the Literata typeface follow
[Voice](https://github.com/PaulWoitaschek/Voice) by Paul Woitaschek. No code is
taken from it. Literata is licensed under the SIL Open Font License.

PDF text extraction uses [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android).
