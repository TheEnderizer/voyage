package com.betteraudio.sync

import com.betteraudio.data.ebook.ParagraphExtractor
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * A **diagnostic**, not a regression test: it reads a real book's `mapping.json` and its epub off
 * disk and reports whether the aligner's char-offset coordinate system agrees with the one
 * [ParagraphExtractor] produces on device. Skipped (via `assumeTrue`) whenever those files aren't
 * present, so it never fails a normal build.
 *
 * It exists because "the highlighted paragraph doesn't match the player" has two very different
 * causes that look identical from the outside: the *conversion* being wrong (a bug in
 * [PositionBridge]) or the *data* being wrong (the aligner counting characters differently from
 * the app, so every offset in the file points somewhere else). Comparing each spine item's
 * `totalChars` against the largest anchor offset recorded for it separates the two in one pass.
 *
 * Point [MAPPING] and [EPUB] at the files to check.
 */
class ShadowSlaveMappingDiagnosticTest {

    private companion object {
        val SCRATCH = System.getProperty("voyage.diag.dir")
            ?: "C:/Users/hajmo/AppData/Local/Temp/claude/D--/23b559be-e41b-4b88-be11-d2bf12167882/scratchpad"
        val MAPPING = File("$SCRATCH/mapping.json")
        val EPUB = File("$SCRATCH/ShadowSlave.epub")
    }

    @Test
    fun `anchor char offsets agree with the extractor's char stream`() {
        assumeTrue("no mapping.json/epub to diagnose", MAPPING.isFile && EPUB.isFile)

        // Largest anchor offset per spine index — the aligner's idea of "how far this chapter goes".
        val maxOffset = HashMap<Int, Int>()
        val anchorCount = HashMap<Int, Int>()
        val root = JSONObject(MAPPING.readText())
        val arr = root.getJSONArray("anchors")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val s = o.getInt("spineIndex")
            val c = o.getInt("charOffset")
            maxOffset[s] = maxOf(maxOffset[s] ?: 0, c)
            anchorCount[s] = (anchorCount[s] ?: 0) + 1
        }
        println("mapping: ${arr.length()} anchors over ${maxOffset.size} spine items")
        println("mapping has chapterMap: ${root.has("chapterMap")}")

        // The epub's spine, in reading order, straight out of the OPF.
        val zip = ZipFile(EPUB)
        val hrefs = spineHrefs(zip)
        println("epub: ${hrefs.size} spine items")

        var checked = 0
        var overrun = 0
        val ratios = ArrayList<Double>()
        for ((spineIndex, href) in hrefs.withIndex()) {
            val max = maxOffset[spineIndex] ?: continue
            val entry = zip.getEntry(href) ?: continue
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            val total = ParagraphExtractor.extract(bytes).totalChars
            if (total <= 0) continue
            checked++
            ratios.add(max.toDouble() / total)
            if (max > total) {
                overrun++
                if (overrun <= 10) {
                    println("OVERRUN spine $spineIndex ($href): maxAnchor=$max > totalChars=$total")
                }
            }
            if (checked <= 15) {
                println(
                    "spine %4d  anchors=%2d  maxAnchor=%6d  totalChars=%6d  ratio=%.3f  %s"
                        .format(spineIndex, anchorCount[spineIndex], max, total, max.toDouble() / total, href)
                )
            }
        }
        zip.close()

        ratios.sort()
        fun pct(p: Double) = if (ratios.isEmpty()) 0.0 else ratios[(ratios.size * p).toInt().coerceAtMost(ratios.size - 1)]
        println("--------")
        println("spines checked: $checked, anchor offset beyond extractor totalChars: $overrun")
        println(
            "maxAnchor/totalChars  p05=%.3f  p50=%.3f  p95=%.3f".format(pct(0.05), pct(0.50), pct(0.95))
        )
        println("(expected: every ratio slightly below 1.0 — the last anchor sits near the chapter end)")
    }

    /**
     * How much of the real timeline lands on the spine-straddling first leg — the branch that used
     * to measure from a chapter's start instead of from the previous anchor. Reports the share of
     * sampled positions affected and how far out the answer was, in characters of text.
     */
    @Test
    fun `how much of the timeline hits the straddling first leg`() {
        assumeTrue("no mapping.json/epub to diagnose", MAPPING.isFile && EPUB.isFile)

        val arr = JSONObject(MAPPING.readText()).getJSONArray("anchors")
        val anchors = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            AnchorPoint(o.getLong("audioMs"), o.getInt("spineIndex"), o.getInt("charOffset"))
        }.sortedBy { it.audioMs }

        val zip = ZipFile(EPUB)
        val hrefs = spineHrefs(zip)
        val totals = HashMap<Int, Int>()
        fun totalCharsFor(spine: Int): Int = totals.getOrPut(spine) {
            val href = hrefs.getOrNull(spine) ?: return@getOrPut 0
            val entry = zip.getEntry(href) ?: return@getOrPut 0
            ParagraphExtractor.extract(zip.getInputStream(entry).use { it.readBytes() }).totalChars
        }

        // Walk the timeline at 5-second steps and classify each position.
        val endMs = anchors.last().audioMs
        var samples = 0
        var straddling = 0
        var firstLeg = 0
        val errors = ArrayList<Int>()
        var ms = anchors.first().audioMs
        while (ms <= endMs) {
            samples++
            val i = anchors.indexOfLast { it.audioMs <= ms }
            if (i in 0 until anchors.size - 1) {
                val prev = anchors[i]
                val next = anchors[i + 1]
                if (prev.spineIndex != next.spineIndex && next.audioMs > prev.audioMs) {
                    straddling++
                    val t = (ms - prev.audioMs).toFloat() / (next.audioMs - prev.audioMs)
                    val d1 = (totalCharsFor(prev.spineIndex).coerceAtLeast(prev.charOffset) - prev.charOffset)
                        .coerceAtLeast(0)
                    val midLens = ((prev.spineIndex + 1) until next.spineIndex).sumOf { totalCharsFor(it).coerceAtLeast(0) }
                    val totalPath = (d1 + midLens + next.charOffset.coerceAtLeast(0)).coerceAtLeast(1)
                    if ((t * totalPath) <= d1) {
                        firstLeg++
                        errors.add(prev.charOffset) // exactly how far the old answer was displaced
                    }
                }
            }
            ms += 5_000
        }
        zip.close()

        errors.sort()
        val pctStraddle = 100.0 * straddling / samples
        val pctFirstLeg = 100.0 * firstLeg / samples
        println("sampled every 5s: $samples positions across ${endMs / 3_600_000}h of audio")
        println("bracketing anchors straddle a chapter boundary: %.2f%%".format(pctStraddle))
        println("...of which the buggy first leg: %.2f%% of the whole book".format(pctFirstLeg))
        if (errors.isNotEmpty()) {
            println(
                "displacement when it hit, in characters: min=%d median=%d max=%d"
                    .format(errors.first(), errors[errors.size / 2], errors.last())
            )
            println("(a chapter here is ~8000 characters, so the median miss was most of a chapter)")
        }
    }

    /** Spine hrefs in reading order, resolved relative to the OPF's own directory. */
    private fun spineHrefs(zip: ZipFile): List<String> {
        val container = zip.getEntry("META-INF/container.xml") ?: return emptyList()
        val opfPath = Regex("""full-path="([^"]+)"""")
            .find(zip.getInputStream(container).use { it.readBytes().toString(Charsets.UTF_8) })
            ?.groupValues?.get(1) ?: return emptyList()
        val opf = zip.getEntry(opfPath) ?: return emptyList()
        val xml = zip.getInputStream(opf).use { it.readBytes().toString(Charsets.UTF_8) }
        val base = opfPath.substringBeforeLast('/', "")

        val idToHref = Regex("""<item\b[^>]*>""").findAll(xml).mapNotNull { m ->
            val tag = m.value
            val id = Regex("""\bid="([^"]+)"""").find(tag)?.groupValues?.get(1)
            val href = Regex("""\bhref="([^"]+)"""").find(tag)?.groupValues?.get(1)
            if (id != null && href != null) id to href else null
        }.toMap()

        return Regex("""<itemref\b[^>]*\bidref="([^"]+)"""").findAll(xml).mapNotNull { m ->
            idToHref[m.groupValues[1]]?.let { href ->
                val decoded = href.replace("&amp;", "&").replace("%20", " ")
                if (base.isEmpty()) decoded else "$base/$decoded"
            }
        }.toList()
    }
}
