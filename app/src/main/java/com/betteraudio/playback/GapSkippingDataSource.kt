package com.betteraudio.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.betteraudio.util.AppLog
import java.io.EOFException
import java.io.IOException
import java.io.RandomAccessFile

/**
 * A [DataSource] that presents a damaged local file as if the damaged bytes were not there.
 *
 * The extractor sees one continuous, valid stream; underneath, reads jump over the byte ranges
 * [Mp3DamageScanner] found. This is the only workable fix for a download-damaged MP3: ExoPlayer's
 * `Mp3Extractor` abandons the file after searching 128 KB for a valid frame, and Android's own
 * `MediaExtractor` was measured stopping at the same damage, so neither demuxer can be coaxed
 * across a gap of several hundred KB. Removing the gap from what they read sidesteps the limit
 * entirely — the same trick [SkipHeadDataSource] plays for head damage, generalised to a list of
 * ranges anywhere in the file.
 *
 * Usage: build the media URI with [wrapUri]. When this source opens such a URI it strips the
 * marker and remaps every position; URIs without it pass straight through to [upstream] untouched,
 * so normal files keep the ordinary, well-tested code path.
 *
 * Reads are served from a [RandomAccessFile] rather than [upstream] when the marker is present.
 * That is deliberate: skipping a range mid-stream would otherwise mean closing and reopening the
 * upstream source at a new offset on every gap. Only local `file://` URIs are ever marked (the
 * scanner needs a real path anyway), so nothing else is affected.
 *
 * Positions in [DataSpec] are *logical* — offsets into the gap-free stream the extractor believes
 * it is reading. [toPhysical] maps those onto real file offsets.
 */
@UnstableApi
class GapSkippingDataSource(private val upstream: DataSource) : DataSource {

    companion object {
        const val PARAM_GAPS = "voyageGaps"

        /** Returns [uri] carrying [gaps], or [uri] unchanged when there is nothing to skip. */
        fun wrapUri(uri: Uri, gaps: List<Mp3DamageScanner.Gap>): Uri {
            if (gaps.isEmpty()) return uri
            return uri.buildUpon()
                .appendQueryParameter(PARAM_GAPS, Mp3DamageScanner.encode(gaps))
                .build()
        }

        fun gapsIn(uri: Uri): List<Mp3DamageScanner.Gap> =
            Mp3DamageScanner.decode(runCatching { uri.getQueryParameter(PARAM_GAPS) }.getOrNull())

        fun isMarked(uri: Uri): Boolean =
            runCatching { uri.getQueryParameter(PARAM_GAPS) != null }.getOrDefault(false)
    }

    private var delegating = true
    private var raf: RandomAccessFile? = null
    private var openedUri: Uri? = null

    private var map: GapMap? = null
    private var logicalPos = 0L
    private var bytesRemaining = 0L
    /** Last physical offset the file pointer is known to sit at, to avoid redundant seeks. */
    private var physCursor = -1L

    override fun open(dataSpec: DataSpec): Long {
        val parsed = gapsIn(dataSpec.uri)
        if (parsed.isEmpty()) {
            delegating = true
            return upstream.open(dataSpec)
        }
        delegating = false

        val path = dataSpec.uri.path ?: throw IOException("No file path in ${dataSpec.uri}")
        val f = RandomAccessFile(path, "r")
        raf = f
        val m = GapMap(parsed, f.length())
        map = m

        logicalPos = dataSpec.position
        if (logicalPos > m.logicalSize) {
            close()
            throw EOFException("position ${dataSpec.position} past logical end ${m.logicalSize}")
        }
        physCursor = -1L
        bytesRemaining =
            if (dataSpec.length == C.LENGTH_UNSET.toLong()) m.logicalSize - logicalPos
            else minOf(dataSpec.length, m.logicalSize - logicalPos)

        openedUri = dataSpec.uri.buildUpon().clearQuery().build()
        AppLog.i(
            "Damage",
            "gap-skip open ${path.substringAfterLast('/')}: ${m.gaps.size} gap(s), " +
                "physical=${m.physicalSize} logical=${m.logicalSize} from=$logicalPos serving=$bytesRemaining"
        )
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (delegating) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        if (bytesRemaining <= 0L) return C.RESULT_END_OF_INPUT
        val f = raf ?: return C.RESULT_END_OF_INPUT
        val m = map ?: return C.RESULT_END_OF_INPUT

        val phys = m.toPhysical(logicalPos)
        // Never read across a gap in one go — stop at its edge and let the next call resume on the
        // far side (toPhysical jumps it, since logicalPos will have advanced to the boundary).
        val room = minOf(
            length.toLong(),
            bytesRemaining,
            m.bytesUntilNextGap(phys)
        ).toInt()
        if (room <= 0) return C.RESULT_END_OF_INPUT

        if (physCursor != phys) {
            f.seek(phys)
            physCursor = phys
        }
        val n = f.read(buffer, offset, room)
        if (n <= 0) return C.RESULT_END_OF_INPUT
        logicalPos += n
        physCursor += n
        bytesRemaining -= n
        return n
    }

    override fun getUri(): Uri? = if (delegating) upstream.uri else openedUri

    override fun close() {
        if (delegating) {
            upstream.close()
            return
        }
        try {
            raf?.close()
        } catch (e: Throwable) {
            AppLog.e("Damage", "closing gap-skipping source failed", e)
        } finally {
            raf = null
            openedUri = null
            physCursor = -1L
            bytesRemaining = 0L
        }
    }

    // Always registered upstream: harmless when we serve reads ourselves (local files report no
    // meaningful transfer stats) and required when we delegate.
    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

}

/**
 * The logical↔physical arithmetic behind [GapSkippingDataSource], kept free of Android types so it
 * can be unit-tested directly — it is the part that must be exactly right, since an off-by-one
 * here corrupts the audio stream rather than failing loudly.
 *
 * "Physical" is a real offset in the file on disk. "Logical" is an offset in the gap-free stream
 * the extractor believes it is reading.
 */
class GapMap(rawGaps: List<Mp3DamageScanner.Gap>, val physicalSize: Long) {

    /** Sorted, merged, clamped to the file — so the arithmetic can assume well-formed input. */
    val gaps: List<Mp3DamageScanner.Gap> = run {
        val clamped = rawGaps.asSequence()
            .map {
                Mp3DamageScanner.Gap(
                    it.start.coerceIn(0L, physicalSize),
                    it.end.coerceIn(0L, physicalSize)
                )
            }
            .filter { it.end > it.start }
            .sortedBy { it.start }
            .toList()
        if (clamped.isEmpty()) emptyList()
        else {
            val merged = ArrayList<Mp3DamageScanner.Gap>(clamped.size)
            var cur = clamped[0]
            for (i in 1 until clamped.size) {
                val g = clamped[i]
                // `<=` so touching ranges collapse too: [0,10)+[10,20) is one gap, not two, which
                // keeps toPhysical's single forward pass correct.
                cur = if (g.start <= cur.end) Mp3DamageScanner.Gap(cur.start, maxOf(cur.end, g.end))
                else { merged.add(cur); g }
            }
            merged.add(cur)
            merged
        }
    }

    val logicalSize: Long = (physicalSize - gaps.sumOf { it.size }).coerceAtLeast(0L)

    /**
     * Logical offset → physical offset. Walks the gaps in order, pushing the result past each one
     * it has reached; because the list is sorted and merged, skipping one gap can only move the
     * cursor toward the next, so a single forward pass is exact.
     */
    fun toPhysical(logical: Long): Long {
        var phys = logical
        for (g in gaps) {
            if (phys >= g.start) phys += g.size else break
        }
        return phys
    }

    /** Readable bytes at [phys] before the next gap (or EOF) interrupts. */
    fun bytesUntilNextGap(phys: Long): Long {
        for (g in gaps) if (g.start > phys) return g.start - phys
        return physicalSize - phys
    }
}
