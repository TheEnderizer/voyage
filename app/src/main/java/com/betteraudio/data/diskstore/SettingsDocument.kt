package com.betteraudio.data.diskstore

/**
 * In-memory shape of `<libraryRoot>/.voyage/settings.json` — a flat list of {name, type, value}
 * triples, exactly BackupManager's SettingSpec shape (now shared via [SettingsSpecs]) so the
 * same parser applies both a disk mirror and a manual backup import.
 */
data class SettingsDocument(
    val version: Int = CURRENT_VERSION,
    val writtenAt: Long,
    /** Diagnostic only — the library folder path at the moment this file was written. Never
     *  read back: LIBRARY_FOLDER cannot live in the folder it names. */
    val libraryFolderAtWrite: String,
    val settings: List<SettingValue> = emptyList(),
    val unknown: Map<String, Any?> = emptyMap()
) {
    data class SettingValue(val name: String, val type: String, val value: String)

    companion object {
        const val CURRENT_VERSION = 1
    }
}
