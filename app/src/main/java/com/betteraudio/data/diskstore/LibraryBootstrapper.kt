package com.betteraudio.data.diskstore

import com.betteraudio.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** UNKNOWN = DataStore not yet emitted (never persisted — see [SettingsStore.currentSetupState]'s
 *  own doc comment); NEEDS_FOLDER = library folder blank; FRESH = onboarding should run normally;
 *  RESTORED = a `.voyage/settings.json` with real onboarding answers was found and applied. Only
 *  FRESH/RESTORED are ever written to disk — see [LibraryBootstrapper.bootstrap]. */
enum class SetupState {
    UNKNOWN, NEEDS_FOLDER, FRESH, RESTORED;

    companion object {
        /** Parses [SettingsStore.currentSetupState]/`.setupState`'s raw string — "" (never set)
         *  and anything unrecognized both fall back to UNKNOWN, never to a terminal value. */
        fun from(raw: String): SetupState = entries.find { it.name == raw } ?: UNKNOWN
    }
}

/**
 * Runs once, right after the user picks a library folder (first run or post-reinstall) — detects
 * a pre-existing `.voyage/settings.json`, applies it, and decides whether the onboarding dialogs
 * (theme picker, import-structure picker) should show. Deliberately does NOT touch
 * `.voyage/library.json` (presets/authors/series) here: at this point no book has been scanned
 * yet, so there's nothing for RestoreOps.applySeries to match series members against. Instead it
 * resets [SettingsStore.libraryJsonAppliedAt] to 0 so the scan the caller runs immediately after
 * (see HomeViewModel.startScan) has AudioFileScanner.reconcileLibraryFromDisk actually apply it,
 * once real book candidates exist.
 */
@Singleton
class LibraryBootstrapper @Inject constructor(
    private val settings: SettingsStore,
    private val settingsFileStore: SettingsFileStore,
    private val widgetsDataStore: WidgetsDataStore
) {
    /** On IO throughout: the only caller is HomeViewModel.startScan's Main-dispatched
     *  viewModelScope, and this reads settings.json, designs.json and copies every widget image
     *  back into filesDir — far too much to do on the frame thread during onboarding. */
    suspend fun bootstrap(libraryFolder: String): SetupState = withContext(Dispatchers.IO) {
        settings.setLibraryFolder(libraryFolder)
        var terminal = SetupState.FRESH
        try {
            val doc = settingsFileStore.read(libraryFolder)
            if (doc != null && hasOnboardingAnswers(doc)) {
                applySettingsDoc(doc)
                settings.setLibraryJsonAppliedAt(0L)
                terminal = SetupState.RESTORED
                // Unlike library.json's presets/authors/series, widget designs don't need a
                // scanned book to match against — restore them right away rather than waiting
                // for reconcileLibraryFromDisk.
                runCatching { widgetsDataStore.read()?.let { widgetsDataStore.restore(it) } }
            }
        } finally {
            settings.setSetupState(terminal.name)
        }
        terminal
    }

    /** A settings.json written before the user ever answered the theme/structure prompts (e.g. a
     *  very early partial export) carries no real onboarding answers — treated as FRESH so those
     *  dialogs still run, rather than silently skipping them with nothing to show for it. */
    private fun hasOnboardingAnswers(doc: SettingsDocument): Boolean =
        doc.settings.any { it.name == "app_theme" || it.name == "import_structure" }

    private suspend fun applySettingsDoc(doc: SettingsDocument) {
        val coreByName = SettingsSpecs.coreSpecs().associateBy { it.name }
        val pathByName = SettingsSpecs.pathSpecs().associateBy { it.name }
        doc.settings.forEach { sv ->
            runCatching {
                when {
                    sv.name == "gemini_api_key" -> settings.setGeminiApiKey(sv.value)
                    pathByName.containsKey(sv.name) -> {
                        // A path from another device/install is meaningless and would just 404 in
                        // the UI — only apply it if it still resolves on this one.
                        if (sv.value.isNotBlank() && File(sv.value).exists()) pathByName.getValue(sv.name).set(settings, sv.value)
                    }
                    else -> coreByName[sv.name]?.set(settings, sv.value)
                }
            }
        }
    }
}
