package com.betteraudio.data.ebook

import android.net.Uri
import android.util.Xml
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.ZipFile
import kotlin.concurrent.read
import kotlin.concurrent.write

/** EPUB metadata pulled from the OPF `<metadata>` block. */
data class EpubMeta(val title: String?, val author: String?, val coverHref: String?)

/** One spine (reading-order) item. [title] is resolved from the nav/TOC document when available;
 *  null means the UI falls back to "Section n". */
data class SpineItem(val index: Int, val href: String, val title: String?)

data class EpubInfo(val meta: EpubMeta, val spine: List<SpineItem>, val encrypted: Boolean)

/**
 * Minimal EPUB (2/3) reader built entirely on platform APIs — no new Gradle dependency. An EPUB is
 * a zip archive; this class unzips just enough of it (container.xml → OPF → nav/NCX) to drive the
 * reader and the chapter-alignment sync. It intentionally does NOT support DRM (encrypted epubs are
 * flagged via [EpubInfo.encrypted] and refused by callers) or exotic layouts (fixed-layout, RTL
 * vertical scripts) — v1 targets mainstream reflowable EPUB.
 */
class EpubParser(private val epubFile: File) : Closeable {

    private val zip = ZipFile(epubFile)
    private var opfDir: String = ""

    // `ZipFile` is not documented safe for concurrent use, and nothing serialized `readEntry`
    // (background layout, lazy image decode) against `close()` (called from
    // `EbookReaderViewModel.onCleared`, off the composition that's still reading). A read lock
    // around every zip access plus a write lock in `close()` makes both safe: readers already in
    // flight finish before close proceeds, and a read started after close sees [closed] under the
    // same lock and returns null instead of racing a closed `ZipFile` into a `ZipException`.
    private val lock = ReentrantReadWriteLock()
    @Volatile private var closed = false

    fun parse(): EpubInfo {
        val encrypted = lock.read { !closed && zip.getEntry("META-INF/encryption.xml") != null }

        val opfPath = readOpfPath()
        opfDir = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val opfBytes = readZipEntry(opfPath)
        if (opfBytes == null) {
            AppLog.w(LogCat.EBOOK, "EpubParser: could not read OPF at '$opfPath' in ${epubFile.name} — returning empty EpubInfo")
            return EpubInfo(EpubMeta(null, null, null), emptyList(), encrypted)
        }

        val manifest = LinkedHashMap<String, ManifestItem>() // id -> item
        val spineIds = mutableListOf<String>()
        var title: String? = null
        var author: String? = null
        var coverManifestId: String? = null
        var navId: String? = null   // EPUB3 nav doc (manifest item with properties="nav")
        var ncxId: String? = null   // EPUB2 toc.ncx (spine@toc, or manifest media-type ncx)

        parseXml(opfBytes) { parser ->
            when (parser.name) {
                "title" -> if (title == null) title = parser.nextText().trim().takeIf { it.isNotBlank() }
                "creator" -> if (author == null) author = parser.nextText().trim().takeIf { it.isNotBlank() }
                "meta" -> {
                    if (parser.getAttributeValue(null, "name") == "cover") {
                        coverManifestId = parser.getAttributeValue(null, "content")
                    }
                }
                "item" -> {
                    val id = parser.getAttributeValue(null, "id") ?: return@parseXml
                    val href = parser.getAttributeValue(null, "href") ?: return@parseXml
                    val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                    val properties = parser.getAttributeValue(null, "properties") ?: ""
                    manifest[id] = ManifestItem(href, mediaType, properties)
                    if ("cover-image" in properties) coverManifestId = coverManifestId ?: id
                    if ("nav" in properties) navId = id
                    if (mediaType.contains("ncx")) ncxId = id
                }
                "itemref" -> parser.getAttributeValue(null, "idref")?.let { spineIds.add(it) }
                "spine" -> parser.getAttributeValue(null, "toc")?.let { if (ncxId == null) ncxId = it }
            }
        }

        val spineHrefs = spineIds.mapNotNull { manifest[it]?.href }
        val tocTitles = runCatching {
            when {
                navId != null -> manifest[navId]?.href?.let { parseNavToc(it) }
                ncxId != null -> manifest[ncxId]?.href?.let { parseNcxToc(it) }
                else -> null
            }
        }.getOrNull() ?: emptyMap()

        val spine = spineHrefs.mapIndexed { i, href ->
            val key = stripFragment(href)
            SpineItem(i, href, tocTitles[key])
        }

        val coverHref = coverManifestId?.let { manifest[it]?.href }
            ?: manifest.values.firstOrNull { it.mediaType.startsWith("image/") && ("cover" in it.href.lowercase()) }?.href

        return EpubInfo(EpubMeta(title, author, coverHref), spine, encrypted)
    }

    /** Reads a zip entry by an href relative to the OPF directory (fragment/query stripped, `../`
     *  resolved). Returns null if the entry doesn't exist. */
    fun readEntry(href: String): ByteArray? {
        val clean = stripFragment(Uri.decode(href))
        val resolved = resolvePath(opfDir, clean)
        return readZipEntry(resolved)
    }

    fun mimeTypeFor(href: String): String {
        val ext = href.substringAfterLast('.', "").substringBefore('?').lowercase()
        return when (ext) {
            "xhtml", "html", "htm" -> "text/html"
            "css" -> "text/css"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "js" -> "text/javascript"
            else -> "application/octet-stream"
        }
    }

    /** Writes the cover image (if any) to [target]. Returns false when the epub has no cover. */
    /** Raw cover bytes, or null if the epub declares no cover / can't be read. The caller decides
     *  where they land — a book's canonical cover location depends on its folderKey shape, so it's
     *  BookDataStore that owns the actual write. */
    fun extractCoverBytes(): ByteArray? {
        val info = runCatching { parse() }.getOrNull() ?: return null
        val href = info.meta.coverHref ?: return null
        return readEntry(href)
    }

    override fun close() {
        lock.write {
            if (closed) return@write
            closed = true
            runCatching { zip.close() }
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private data class ManifestItem(val href: String, val mediaType: String, val properties: String)

    private fun readOpfPath(): String {
        val bytes = readZipEntry("META-INF/container.xml")
        if (bytes == null) {
            AppLog.w(LogCat.EBOOK, "EpubParser: ${epubFile.name} has no META-INF/container.xml — not a valid epub?")
            return ""
        }
        var path = ""
        parseXml(bytes) { parser ->
            if (parser.name == "rootfile") {
                parser.getAttributeValue(null, "full-path")?.let { path = it }
            }
        }
        if (path.isEmpty()) AppLog.w(LogCat.EBOOK, "EpubParser: ${epubFile.name}'s container.xml has no rootfile full-path")
        return path
    }

    /** EPUB3 nav doc: `<nav epub:type="toc">` → `<li><a href="...">Label</a>` anchors. */
    private fun parseNavToc(navHref: String): Map<String, String> {
        val bytes = readZipEntry(resolvePath(opfDir, navHref)) ?: return emptyMap()
        val navDir = navHref.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val result = LinkedHashMap<String, String>()
        var inToc = false
        var tocDepth = 0
        var pendingHref: String? = null
        val textBuf = StringBuilder()
        var collectingText = false

        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(ByteArrayInputStream(bytes), "UTF-8")
        }
        var depth = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    depth++
                    val tag = parser.name.substringAfter(':')
                    if (tag == "nav") {
                        val type = parser.getAttributeValue(null, "type")
                            ?: (0 until parser.attributeCount).firstOrNull {
                                parser.getAttributeName(it).endsWith("type")
                            }?.let { parser.getAttributeValue(it) }
                        if (type == "toc") { inToc = true; tocDepth = depth }
                    }
                    if (inToc && tag == "a") {
                        pendingHref = parser.getAttributeValue(null, "href")
                        collectingText = true
                        textBuf.clear()
                    }
                }
                XmlPullParser.TEXT -> if (collectingText) textBuf.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.substringAfter(':')
                    if (inToc && tag == "a" && pendingHref != null) {
                        val resolvedKey = stripFragment(resolvePath(navDir, pendingHref!!))
                        val label = textBuf.toString().trim()
                        if (label.isNotEmpty() && resolvedKey !in result) result[resolvedKey] = label
                        pendingHref = null
                        collectingText = false
                    }
                    if (inToc && tag == "nav" && depth == tocDepth) inToc = false
                    depth--
                }
            }
            event = parser.next()
        }
        return result
    }

    /** EPUB2 NCX: `<navPoint><navLabel><text>Label</text></navLabel><content src="href"/></navPoint>`. */
    private fun parseNcxToc(ncxHref: String): Map<String, String> {
        val bytes = readZipEntry(resolvePath(opfDir, ncxHref)) ?: return emptyMap()
        val ncxDir = ncxHref.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val result = LinkedHashMap<String, String>()
        var currentLabel: String? = null
        var collectingText = false
        val textBuf = StringBuilder()

        // navLabel/text and content need to stay associated with their enclosing navPoint, which
        // the generic single-pass helper (parseXml) doesn't track — NCX gets its own manual walk.
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(ByteArrayInputStream(bytes), "UTF-8")
        }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.substringAfter(':')
                    when (tag) {
                        "text" -> { collectingText = true; textBuf.clear() }
                        "content" -> {
                            val src = parser.getAttributeValue(null, "src")
                            if (src != null && currentLabel != null) {
                                val key = stripFragment(resolvePath(ncxDir, src))
                                if (key !in result) result[key] = currentLabel!!
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> if (collectingText) textBuf.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.substringAfter(':')
                    if (tag == "text") {
                        collectingText = false
                        textBuf.toString().trim().takeIf { it.isNotEmpty() }?.let { currentLabel = it }
                    }
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun readZipEntry(path: String): ByteArray? {
        if (path.isEmpty()) return null
        return lock.read {
            if (closed) return@read null
            val entry = zip.getEntry(path) ?: zip.getEntry(path.removePrefix("/")) ?: return@read null
            runCatching { zip.getInputStream(entry).use { it.readBytes() } }.getOrNull()
        }
    }

    /** Single-pass START_TAG walker — good enough for flat structures (OPF metadata/manifest/spine,
     *  container.xml) where element nesting/order doesn't matter for the fields we read. */
    private inline fun parseXml(bytes: ByteArray, onStartTag: (XmlPullParser) -> Unit) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                // Namespaced tags ("dc:title", "opf:item") — compare by local name.
                val localParser = LocalNameParser(parser)
                onStartTag(localParser)
            }
            event = parser.next()
        }
    }

    /** Wraps an XmlPullParser so `.name` returns the local (non-prefixed) tag name, since this
     *  parser runs with namespace processing off (Android's XmlPullParser namespace support is
     *  inconsistent across OPF variants that declare `xmlns:dc`/`xmlns:opf` differently). */
    private class LocalNameParser(private val delegate: XmlPullParser) : XmlPullParser by delegate {
        override fun getName(): String = delegate.name?.substringAfter(':') ?: ""
    }

    private fun stripFragment(href: String): String = href.substringBefore('#').substringBefore('?')

    /** Resolves [relative] against [baseDir] (a directory-ending-in-`/`, or ""), collapsing `../`
     *  and `./` segments. Absolute-looking hrefs (start with '/') are used as-is (minus the slash). */
    private fun resolvePath(baseDir: String, relative: String): String {
        if (relative.startsWith("/")) return relative.removePrefix("/")
        val combined = (baseDir + relative)
        val parts = combined.split('/')
        val stack = ArrayDeque<String>()
        for (part in parts) {
            when (part) {
                "", "." -> {}
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(part)
            }
        }
        return stack.joinToString("/")
    }
}
