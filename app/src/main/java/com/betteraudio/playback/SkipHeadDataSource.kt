package com.betteraudio.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import java.io.File
import java.io.RandomAccessFile

/**
 * A [DataSource] wrapper that can hide the first N bytes of a local file from the player.
 *
 * Why: a file whose head is corrupt (garbage / broken frames before clean audio) makes
 * ExoPlayer's extractor fail to synchronize with ERROR_CODE_PARSING_CONTAINER_MALFORMED —
 * and a plain time-seek retry can't help, because with no successful prepare there is no
 * seek map, so re-preparing always re-reads from byte 0 and dies on the same corrupt head.
 * Skipping at the *byte* level means the extractor never sees the damaged region at all.
 *
 * Usage: build the media URI with [wrapUri] (adds a `voyageSkipBytes=N` query parameter).
 * When this source opens such a URI it strips the parameter and offsets every read by N,
 * so the player sees a virtual file that starts at byte N. URIs without the parameter pass
 * through untouched.
 */
@UnstableApi
class SkipHeadDataSource(private val upstream: DataSource) : DataSource {

    companion object {
        const val PARAM_SKIP_BYTES = "voyageSkipBytes"

        /** Returns [uri] with a skip-bytes marker this data source understands. */
        fun wrapUri(uri: Uri, skipBytes: Long): Uri =
            uri.buildUpon().appendQueryParameter(PARAM_SKIP_BYTES, skipBytes.toString()).build()

        /**
         * Size of a leading ID3v2 tag (header + declared body) in [file], or 0 when absent or
         * unreadable. Used so corrupt-head skips can jump past the metadata block first — the
         * damaged audio always sits *after* the tag, and MP3 audio needs no tag to play.
         */
        fun id3TagSizeBytes(file: File): Long = try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(10)
                if (raf.read(header) != 10) return 0L
                if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return 0L
                // Synchsafe 28-bit size (excludes the 10-byte header itself).
                val size = ((header[6].toLong() and 0x7f) shl 21) or
                    ((header[7].toLong() and 0x7f) shl 14) or
                    ((header[8].toLong() and 0x7f) shl 7) or
                    (header[9].toLong() and 0x7f)
                size + 10L
            }
        } catch (_: Throwable) { 0L }
    }

    override fun open(dataSpec: DataSpec): Long {
        val skip = dataSpec.uri.getQueryParameter(PARAM_SKIP_BYTES)?.toLongOrNull() ?: 0L
        if (skip <= 0L) return upstream.open(dataSpec)
        // The caller (PlayerController's corrupt-file recovery) already logs the decision to
        // retry with a head-skip — this confirms the datasource actually received and is
        // applying it, closing the loop in case the two ever disagree.
        AppLog.d(LogCat.PLAYBACK) { "SkipHeadDataSource: skipping $skip head byte(s) of ${dataSpec.uri.buildUpon().clearQuery().build()}" }
        val realUri = dataSpec.uri.buildUpon().clearQuery().build()
        return upstream.open(
            dataSpec.buildUpon()
                .setUri(realUri)
                .setPosition(dataSpec.position + skip)
                .build()
        )
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun close() = upstream.close()

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)
}
