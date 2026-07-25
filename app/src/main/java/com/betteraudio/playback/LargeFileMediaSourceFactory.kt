package com.betteraudio.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.betteraudio.data.scanner.Mp4Boxes
import com.betteraudio.data.scanner.Mp4Probe
import com.betteraudio.util.AppLog
import java.io.File

/**
 * Routes very large single-file MP4/M4A/M4B audiobooks around ExoPlayer's `Mp4Extractor`, demuxing
 * them with the platform-native [android.media.MediaExtractor] via
 * [MediaExtractorProgressiveExtractor] instead.
 *
 * Why: `Mp4Extractor` builds its whole seek table (offset/size/timestamp/flags per sample) on the
 * Java heap — ~24 bytes/sample, so a 261 h / 22M-frame book needs ~505 MB and can never be played,
 * even with `android:largeHeap`. `MediaExtractor` keeps that table in native memory (the same
 * mechanism `data/files/LargeAudioSplitter` used to exploit), so routing only the giant files here
 * lets them play untouched while every normal book keeps using the default, well-tested path.
 *
 * (An earlier attempt using `android.media.MediaParser` failed: on at least some OEM builds it just
 * bundles a Java copy of `Mp4Extractor` and OOMs identically. `MediaExtractor` is the real native
 * demuxer and works on all supported API levels, so there is no version gate here.)
 */
@UnstableApi
class LargeFileMediaSourceFactory(
    private val dataSourceFactory: DataSource.Factory,
) : MediaSource.Factory {

    companion object {
        /** Below this sample count, `largeHeap` already covers it — no need to reroute. */
        const val LARGE_FILE_SAMPLE_THRESHOLD = 8_000_000L

        /** Skip the (cheap but not free) box-header probe for files too small to matter. */
        private const val MIN_PROBE_BYTES = 300L * 1024 * 1024
    }

    private val defaultFactory = DefaultMediaSourceFactory(dataSourceFactory)

    private val nativeDemuxFactory =
        ProgressiveMediaSource.Factory(dataSourceFactory, MediaExtractorProgressiveExtractor.FACTORY)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val factory = if (routeToNativeDemux(mediaItem)) nativeDemuxFactory else defaultFactory
        return factory.createMediaSource(mediaItem)
    }

    private fun routeToNativeDemux(mediaItem: MediaItem): Boolean {
        // Read the path from the URI's path segment, not its full string form: a corrupt-file
        // recovery retry (PlayerController.tryRecoverFromCorruptFile) re-enters this factory with
        // SkipHeadDataSource.wrapUri()'s `?voyageSkipBytes=N` query appended, which a string-suffix
        // check would miss and silently fall back to Mp4Extractor (re-OOMing the same giant file).
        val uri = mediaItem.localConfiguration?.uri ?: return false
        if (uri.scheme != "file") return false
        val path = uri.path ?: return false
        val file = File(path)
        if (file.length() < MIN_PROBE_BYTES) return false
        val ext = file.extension
        if (ext.lowercase() !in Mp4Boxes.MP4_EXTS) return false
        return try {
            Mp4Probe.sampleCount(path, ext) > LARGE_FILE_SAMPLE_THRESHOLD
        } catch (e: Exception) {
            AppLog.e("LargeFileMediaSourceFactory", "probe failed for $path", e)
            false
        }
    }

    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: DrmSessionManagerProvider
    ): MediaSource.Factory {
        defaultFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
        nativeDemuxFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: LoadErrorHandlingPolicy
    ): MediaSource.Factory {
        defaultFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        nativeDemuxFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun getSupportedTypes(): IntArray = defaultFactory.supportedTypes
}
