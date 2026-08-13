package com.betteraudio.data.diskstore

import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
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
            if (doc == null) {
                AppLog.i(LogCat.LIBRARY, "bootstrap: no .voyage/settings.json at '$libraryFolder' — FRESH (first run or genuinely new folder)")
            } else if (!hasOnboardingAnswers(doc)) {
                AppLog.i(LogCat.LIBRARY, "bootstrap: settings.json found but has no onboarding answers (${doc.settings.size} value(s)) — treating as FRESH so the prompts still run")
            } else {
                AppLog.i(LogCat.LIBRARY, "bootstrap: settings.json found with onboarding answers (${doc.settings.size} value(s)) — restoring")
                applySettingsDoc(doc)
                settings.setLibraryJsonAppliedAt(0L)
                terminal = SetupState.RESTORED
                // Unlike library.json's presets/authors/series, widget designs don't need a
                // scanned book to match against — restore them right away rather than waiting
                // for reconcileLibraryFromDisk.
                val widgetsDoc = runCatching { widgetsDataStore.read() }
                    .onFailure { AppLog.w(LogCat.LIBRARY, "bootstrap: reading designs.json failed: ${it.message}") }
                    .getOrNull()
                if (widgetsDoc != null) {
                    runCatching { widgetsDataStore.restore(widgetsDoc) }
                        .onFailure { AppLog.w(LogCat.LIBRARY, "bootstrap: restoring widget designs failed: ${it.message}") }
                } else {
                    AppLog.i(LogCat.LIBRARY, "bootstrap: no designs.json to restore")
                }
            }
        } catch (e: Throwable) {
            // bootstrap() itself has no outer try/catch upstream (HomeViewModel.startScan calls it
            // directly), so an uncaught exception here would abort the whole first-run/reinstall
            // flow with nothing recorded. Fall back to FRESH rather than leaving setup half-done.
            AppLog.e(LogCat.LIBRARY, "bootstrap: failed for '$libraryFolder', falling back to FRESH", e)
            terminal = SetupState.FRESH
        } finally {
            AppLog.i(LogCat.LIBRARY, "bootstrap: setupState=$terminal for '$libraryFolder'")
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
        var applied = 0
        var skippedUnknown = 0
        var skippedStalePath = 0
        var failed = 0
        doc.settings.forEach { sv ->
            runCatching {
                when {
                    sv.name == "gemini_api_key" -> { settings.setGeminiApiKey(sv.value); applied++ }
                    pathByName.containsKey(sv.name) -> {
                        // A path from another device/install is meaningless and would just 404 in
                        // the UI — only apply it if it still resolves on this one.
                        if (sv.value.isNotBlank() && File(sv.value).exists()) {
                            pathByName.getValue(sv.name).set(settings, sv.value)
                            applied++
                        } else {
                            skippedStalePath++
                        }
                    }
                    coreByName.containsKey(sv.name) -> { coreByName.getValue(sv.name).set(settings, sv.value); applied++ }
                    else -> skippedUnknown++ // e.g. a key from a newer app version, or a deliberately-excluded one (see SettingsSpecs)
                }
            }.onFailure { e ->
                failed++
                // Swallowed per-key so one bad value can't abort the whole restore — but silently,
                // today, which is exactly the gap this line closes: which key, and why.
                AppLog.w(LogCat.LIBRARY, "bootstrap: applying setting '${sv.name}'='${sv.value}' failed: ${e.message}")
            }
        }
        AppLog.i(LogCat.LIBRARY, "bootstrap: applySettingsDoc applied=$applied skippedUnknown=$skippedUnknown skippedStalePath=$skippedStalePath failed=$failed of ${doc.settings.size}")
    }
}
