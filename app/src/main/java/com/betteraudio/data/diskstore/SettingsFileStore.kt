package com.betteraudio.data.diskstore

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes `<libraryRoot>/.voyage/settings.json`. Deliberately takes the library folder
 * as a parameter rather than injecting [com.betteraudio.data.settings.SettingsStore] — that store
 * needs to hold a reference to this one (every setter mirrors on write), so the reverse dependency
 * would be a Hilt cycle. This class knows nothing about DataStore or any individual setting; it
 * just persists whatever `{name, type, value}` list it's handed.
 */
@Singleton
class SettingsFileStore @Inject constructor() {

    suspend fun write(libraryFolder: String, values: List<SettingsDocument.SettingValue>): Boolean {
        val file = VoyageLayout.settingsFile(libraryFolder) ?: return false
        val existing = readDocFile(file)
        val doc = SettingsDocument(
            writtenAt = System.currentTimeMillis(),
            libraryFolderAtWrite = libraryFolder,
            settings = values,
            unknown = existing?.unknown ?: emptyMap()
        )
        val ok = writeTextAtomic(file, SettingsDataCodec.encodeToString(doc))
        if (ok) file.parentFile?.let(::ensureNoMedia)
        return ok
    }

    fun read(libraryFolder: String): SettingsDocument? = readDocFile(VoyageLayout.settingsFile(libraryFolder))

    fun lastModified(libraryFolder: String): Long {
        val file = VoyageLayout.settingsFile(libraryFolder) ?: return 0L
        return if (file.isFile) file.lastModified() else 0L
    }

    private fun readDocFile(file: File?): SettingsDocument? {
        if (file == null || !file.isFile) return null
        return runCatching { file.readText() }.getOrNull()?.let { SettingsDataCodec.decodeOrNull(it) }
    }
}
