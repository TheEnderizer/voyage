package com.betteraudio.data.diskstore

import com.betteraudio.data.settings.SettingsStore
import kotlinx.coroutines.flow.first

/**
 * One mirrored setting: [name] is the on-disk key, [type] is "string" | "int" | "long" |
 * "float" | "boolean", [get] reads the current value as a string (or null to omit it from the
 * document — an unset/blank optional), [set] applies a string value back. Deliberately explicit
 * per key rather than reflection-based, so a type mismatch between the JSON and the setter is a
 * compile error, not a runtime crash when applying an old or foreign settings.json — the same
 * reasoning BackupManager's original SettingSpec was built on.
 */
data class SettingSpec(
    val name: String,
    val type: String,
    val get: suspend (SettingsStore) -> String?,
    val set: suspend (SettingsStore, String) -> Unit
)

/**
 * The canonical list of settings mirrored to `<libraryRoot>/.voyage/settings.json`, and also the
 * whitelist a manual backup export/import uses (BackupManager delegates to this instead of
 * keeping its own copy, so the two paths can never drift apart).
 *
 * Deliberately excluded (device-local or meaningless on another device/after a reinstall):
 * `library_folder` (cannot live in the folder it names), `last_open_book_id`,
 * `last_played_book_id`, `theme_book_id`, `app_stopped_at`, `widget_app_color`,
 * `skipped_update_version`, `auto_backup_folder_uri` (a SAF grant bound to this install),
 * `auto_backup_enabled` (restoring it without its folder URI would leave AutoBackupWorker
 * failing on every run), `auto_backup_last_run_ms`/`auto_backup_last_status`,
 * `phantom_series_cleanup_done`, and the disk-mirror's own bootstrap/version flags.
 *
 * Also excluded: `log_level`, `log_budget_mb`, and `enable_file_logging`. Logging is an opt-in
 * diagnostic toggle, off by default — mirroring it would mean a "clean" reinstall silently turns
 * it back on (or restores Verbose + a large budget) instead of actually starting clean. Unlike
 * the settings above, `enable_file_logging` WAS mirrored here once (pre-tri-state); if it still
 * shows up in an old settings.json, it is simply ignored, same as any other unrecognized key.
 *
 * `default_audio_preset_id` is a row id, not portable — it is represented on disk as
 * `default_preset_name` and resolved by name after presets are restored (mirrors
 * BackupManager.restorePresets' own default-resolution step). That resolution needs both
 * SettingsStore and the preset repository, so it is handled by the caller (RestoreOps /
 * DiskMirror), not as a plain SettingSpec here.
 */
object SettingsSpecs {

    private fun spec(
        name: String,
        type: String,
        get: suspend (SettingsStore) -> String?,
        set: suspend (SettingsStore, String) -> Unit
    ) = SettingSpec(name, type, get, set)

    fun coreSpecs(): List<SettingSpec> = listOf(
        spec("skip_forward_ms", "long", { it.skipForwardMs.first().toString() }, { s, v -> s.setSkipForwardMs(v.toLong()) }),
        spec("skip_back_ms", "long", { it.skipBackMs.first().toString() }, { s, v -> s.setSkipBackMs(v.toLong()) }),
        spec("default_speed", "float", { it.defaultSpeed.first().toString() }, { s, v -> s.setDefaultSpeed(v.toFloat()) }),
        spec("sort_option", "string", { it.sortOption.first() }, { s, v -> s.setSort(v, s.sortDirection.first()) }),
        spec("sort_direction", "string", { it.sortDirection.first() }, { s, v -> s.setSort(s.sortOption.first(), v) }),
        spec("auto_rewind_seconds", "int", { it.autoRewindSeconds.first().toString() }, { s, v -> s.setAutoRewindSeconds(v.toInt()) }),
        spec("auto_rewind_threshold_minutes", "int", { it.autoRewindThresholdMinutes.first().toString() }, { s, v -> s.setAutoRewindThresholdMinutes(v.toInt()) }),
        spec("skip_silence_min_ms", "long", { it.skipSilenceMinMs.first().toString() }, { s, v -> s.setSkipSilenceMinMs(v.toLong()) }),
        spec("skip_silence_threshold", "int", { it.skipSilenceThreshold.first().toString() }, { s, v -> s.setSkipSilenceThreshold(v.toInt()) }),
        spec("skip_silence_padding_ms", "long", { it.skipSilencePaddingMs.first().toString() }, { s, v -> s.setSkipSilencePaddingMs(v.toLong()) }),
        // "" omitted on export ("not chosen yet") — this is what lets the bootstrap flow tell a
        // genuine restore apart from a settings.json that never saw the onboarding prompts.
        spec("import_structure", "string", { it.importStructure.first().takeIf(String::isNotBlank) }, { s, v -> s.setImportStructure(v) }),
        spec("home_view_mode", "string", { it.homeViewMode.first() }, { s, v -> s.setHomeViewMode(v) }),
        spec("player_show_series_cover", "boolean", { it.playerShowSeriesCover.first().toString() }, { s, v -> s.setPlayerShowSeriesCover(v.toBoolean()) }),
        spec("app_theme", "string", { it.appTheme.first().takeIf(String::isNotBlank) }, { s, v -> s.setAppTheme(v) }),
        spec("theme_color_source", "string", { it.themeColorSource.first() }, { s, v -> s.setThemeColorSource(v) }),
        spec("custom_theme_color", "string", { it.customThemeColor.first() }, { s, v -> s.setCustomThemeColor(v) }),
        // Manual per-cover accent picks. Omitted when empty so a settings.json from a user who
        // never pinned one stays free of an empty object.
        spec("cover_accents", "string", { it.coverAccents.first().takeIf(String::isNotBlank) }, { s, v -> s.setCoverAccentsRaw(v) }),
        spec("dark_mode", "string", { it.darkMode.first() }, { s, v -> s.setDarkMode(v) }),
        spec("pure_black", "boolean", { it.pureBlack.first().toString() }, { s, v -> s.setPureBlack(v.toBoolean()) }),
        spec("home_section", "string", { it.homeSection.first() }, { s, v -> s.setHomeSection(v) }),
        spec("widget_hide_when_idle", "boolean", { it.widgetHideWhenIdle.first().toString() }, { s, v -> s.setWidgetHideWhenIdle(v.toBoolean()) }),
        spec("dynamic_pills", "boolean", { it.dynamicPills.first().toString() }, { s, v -> s.setDynamicPills(v.toBoolean()) }),
        spec("mini_cover_style", "string", { it.miniCoverStyle.first() }, { s, v -> s.setMiniCoverStyle(v) }),
        spec("scrubber_style", "string", { it.scrubberStyle.first() }, { s, v -> s.setScrubberStyle(v) }),
        spec("scrubber_style_material", "string", { it.scrubberStyleMaterial.first() }, { s, v -> s.setScrubberStyleMaterial(v) }),
        // One accent for every cover. Omitted when off, so a settings.json from a user who never
        // turned it on stays free of an empty override.
        spec("global_accent", "string", { it.globalAccent.first().takeIf(String::isNotBlank) }, { s, v -> s.setGlobalAccent(v) }),
        spec("haptic_strength", "string", { it.hapticStrength.first() }, { s, v -> s.setHapticStrength(v) }),
        spec("sleep_fade_seconds", "int", { it.sleepFadeSeconds.first().toString() }, { s, v -> s.setSleepFadeSeconds(v.toInt()) }),
        spec("sleep_shake_enabled", "boolean", { it.sleepShakeEnabled.first().toString() }, { s, v -> s.setSleepShakeEnabled(v.toBoolean()) }),
        spec("sleep_shake_reset_minutes", "int", { it.sleepShakeResetMinutes.first().toString() }, { s, v -> s.setSleepShakeResetMinutes(v.toInt()) }),
        spec("sleep_schedule_enabled", "boolean", { it.sleepScheduleEnabled.first().toString() }, { s, v -> s.setSleepScheduleEnabled(v.toBoolean()) }),
        spec("sleep_schedule_start_minutes", "int", { it.sleepScheduleStartMinutes.first().toString() }, { s, v -> s.setSleepScheduleStartMinutes(v.toInt()) }),
        spec("sleep_schedule_end_minutes", "int", { it.sleepScheduleEndMinutes.first().toString() }, { s, v -> s.setSleepScheduleEndMinutes(v.toInt()) }),
        spec("sleep_schedule_default_minutes", "int", { it.sleepScheduleDefaultMinutes.first().toString() }, { s, v -> s.setSleepScheduleDefaultMinutes(v.toInt()) }),
        spec("sleep_timer_minutes", "int", { it.sleepTimerMinutes.first().toString() }, { s, v -> s.setSleepTimerMinutes(v.toInt()) }),
        spec("audio_balance", "float", { it.audioBalance.first().toString() }, { s, v -> s.setAudioBalance(v.toFloat()) }),
        spec("mono_audio", "boolean", { it.monoAudio.first().toString() }, { s, v -> s.setMonoAudio(v.toBoolean()) }),
        spec("headset_multi_press_enabled", "boolean", { it.headsetMultiPressEnabled.first().toString() }, { s, v -> s.setHeadsetMultiPressEnabled(v.toBoolean()) }),
        spec("headset_double_press_action", "string", { it.headsetDoublePressAction.first() }, { s, v -> s.setHeadsetDoublePressAction(v) }),
        spec("headset_triple_press_action", "string", { it.headsetTriplePressAction.first() }, { s, v -> s.setHeadsetTriplePressAction(v) }),
        spec("bt_auto_resume_enabled", "boolean", { it.btAutoResumeEnabled.first().toString() }, { s, v -> s.setBtAutoResumeEnabled(v.toBoolean()) }),
        spec("bt_auto_resume_window_minutes", "int", { it.btAutoResumeWindowMinutes.first().toString() }, { s, v -> s.setBtAutoResumeWindowMinutes(v.toInt()) }),
        spec("backup_include_api_key", "boolean", { it.backupIncludeApiKey.first().toString() }, { s, v -> s.setBackupIncludeApiKey(v.toBoolean()) }),
        spec(
            "widget_custom_colors", "string",
            { it.widgetCustomColors.first().joinToString(",").takeIf(String::isNotBlank) },
            { s, v -> s.setWidgetCustomColorsCsv(v) }
        ),
    )

    /** Path-valued settings: mirrored always, but restored only if the path still exists on this
     *  device/volume — a path from another device or a reinstalled library elsewhere is
     *  meaningless and would just 404 in the UI. The existence check is the caller's job (it
     *  needs java.io.File, which this pure list deliberately avoids). */
    fun pathSpecs(): List<SettingSpec> = listOf(
        spec("widget_default_cover_path", "string", { it.widgetDefaultCoverPath.first().takeIf(String::isNotBlank) }, { s, v -> s.setWidgetDefaultCoverPath(v) }),
    )

    /** Opt-in only (default off) — `.voyage/` lives inside a folder the user may sync to a cloud
     *  drive, so the Gemini key is mirrored only when `backup_include_api_key` is on, matching
     *  the existing manual-export policy. Caller checks that flag before including this spec. */
    fun apiKeySpec(): SettingSpec =
        spec("gemini_api_key", "string", { it.geminiApiKey.first().takeIf(String::isNotBlank) }, { s, v -> s.setGeminiApiKey(v) })
}
