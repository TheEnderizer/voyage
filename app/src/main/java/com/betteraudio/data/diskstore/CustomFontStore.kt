package com.betteraudio.data.diskstore

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brings a font file the user picked into the app, and keeps it somewhere it will still be next
 * time.
 *
 * **Why it is copied at all.** The picker hands back a `content://` Uri, which is a permission to
 * read one file *right now*, not a path. It stops resolving when the app restarts, when the
 * provider's grant is dropped, or when the user tidies their Downloads folder — and the app's type
 * is not a thing that may quietly disappear between launches. So the bytes are copied once and the
 * app only ever reads its own copy.
 *
 * **Where the copy goes.** `<libraryRoot>/.voyage/fonts/` when there is a library folder, which is
 * the same reinstall-proofing every other precious thing in Voyage gets: the settings mirror
 * records the path in `settings.json`, the file sits beside it, and a reinstall that points at the
 * same folder finds both (see the storage model in CLAUDE.md). With no library folder chosen yet it
 * falls back to `filesDir/fonts/`, which is honest rather than clever — that copy does not survive
 * an uninstall, and there is nowhere durable to put it until the user has told us where their
 * library is.
 *
 * **Validation is a load, not a guess.** Extensions lie, and a file that is not a font produces a
 * silently default-looking app rather than an error, which is the worst of both. The import builds
 * a real [Typeface] from the written file and refuses the import if Android will not have it —
 * `createFromFile` answers `Typeface.DEFAULT` for a file it cannot parse on some versions and
 * throws on others, so both are treated as failure.
 */
@Singleton
class CustomFontStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore
) {
    /** What the import produced, or why it did not. */
    sealed interface Result {
        data class Success(val path: String, val displayName: String, val sizeBytes: Long) : Result
        /** [message] is written to be shown as-is: the user picked a file and wants to know about
         *  that file, not about an exception class. */
        data class Failure(val message: String) : Result
    }

    suspend fun import(uri: Uri): Result = withContext(Dispatchers.IO) {
        val sourceName = displayNameOf(uri) ?: "font"
        val ext = sourceName.substringAfterLast('.', "").lowercase()
        if (ext !in ALLOWED_EXTENSIONS) {
            return@withContext Result.Failure(
                "Voyage reads .ttf, .otf, .ttc and .otc font files. That one is .${ext.ifBlank { "unknown" }}."
            )
        }

        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            return@withContext Result.Failure("That file could not be read.")
        }
        if (bytes.size > MAX_FONT_BYTES) {
            return@withContext Result.Failure(
                "That font is ${bytes.size / 1_000_000} MB. Voyage takes files up to ${MAX_FONT_BYTES / 1_000_000} MB."
            )
        }

        val dir = fontsDir()
        if (!dir.exists() && !dir.mkdirs()) {
            return@withContext Result.Failure("Voyage could not write to its font folder.")
        }

        // One name, overwritten on every import: there is exactly one custom font, and keeping the
        // rejects of previous attempts around would just be litter in the user's library folder.
        // The extension is kept because Android's parser dispatches on the container.
        val target = File(dir, "$FILE_STEM.$ext")
        val previous = File(dir, "$FILE_STEM.$ext.previous")
        if (target.exists()) runCatching { target.copyTo(previous, overwrite = true) }
        val written = runCatching { target.writeBytes(bytes); true }.getOrDefault(false)
        if (!written) {
            return@withContext Result.Failure("Voyage could not write the font to its font folder.")
        }

        val loads = runCatching { Typeface.createFromFile(target) }
            .getOrNull()
            .let { it != null && it != Typeface.DEFAULT }
        if (!loads) {
            // Put back whatever was working before rather than leaving the app pointing at a file
            // Android has just refused.
            if (previous.exists()) runCatching { previous.copyTo(target, overwrite = true) }
            else runCatching { target.delete() }
            runCatching { previous.delete() }
            AppLog.w(LogCat.SETTINGS, "font import: Android would not load '$sourceName'")
            return@withContext Result.Failure(
                "Android could not read that as a font. Voyage kept the one it had."
            )
        }
        runCatching { previous.delete() }

        // Any other font file left over from a previous import in a different container.
        ALLOWED_EXTENSIONS.filter { it != ext }.forEach { stale ->
            runCatching { File(dir, "$FILE_STEM.$stale").delete() }
        }

        AppLog.i(LogCat.SETTINGS, "font import: '$sourceName' -> ${target.absolutePath} (${bytes.size} bytes)")
        Result.Success(target.absolutePath, prettyName(sourceName), bytes.size.toLong())
    }

    /** Deletes the app's copy. Called when the user goes back to Poppins — leaving a megabyte of
     *  unused font in their library folder because they changed their mind is not tidy. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        val dir = fontsDir()
        ALLOWED_EXTENSIONS.forEach { ext -> runCatching { File(dir, "$FILE_STEM.$ext").delete() } }
    }

    /** `.voyage/fonts/` when there is a library folder; the app's own storage until there is. */
    private fun fontsDir(): File {
        val library = settings.currentLibraryFolder
        return VoyageLayout.fontsDir(library) ?: File(context.filesDir, "fonts")
    }

    private fun displayNameOf(uri: Uri): String? {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0) return c.getString(i)
                    }
                }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    companion object {
        private val ALLOWED_EXTENSIONS = listOf("ttf", "otf", "ttc", "otc")
        /** Generous, but not unbounded: a CJK font can legitimately be 20 MB and a corrupt file can
         *  be any size at all. */
        private const val MAX_FONT_BYTES = 40 * 1024 * 1024
        private const val FILE_STEM = "app_font"

        /**
         * A name to show for the file, since a [Typeface] will not tell us its family: the file's
         * own stem, with the style suffix designers append and the separators they use taken off.
         * "Spectral-Regular.ttf" reads as "Spectral", which is what the user calls it.
         */
        fun prettyName(fileName: String): String {
            var stem = fileName.substringBeforeLast('.')
            for (suffix in STYLE_SUFFIXES) {
                for (sep in listOf("-", "_", " ")) {
                    if (stem.endsWith("$sep$suffix", ignoreCase = true)) {
                        stem = stem.dropLast(suffix.length + sep.length)
                        break
                    }
                }
            }
            return stem.replace('_', ' ').replace('-', ' ').trim().ifBlank { fileName }
        }

        private val STYLE_SUFFIXES = listOf("Regular", "Book", "Normal", "Roman", "VariableFont", "Variable")
    }
}
