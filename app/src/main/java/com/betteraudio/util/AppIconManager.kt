package com.betteraudio.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.betteraudio.R
import com.betteraudio.util.log.LogCat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Switches the launcher-visible app icon among a fixed set of pre-baked variants, using the
 * `<activity-alias>` components declared in AndroidManifest.xml (see the comment there) —
 * Android has no API to point the launcher icon at an arbitrary runtime image, so a fixed set of
 * aliases toggled with [PackageManager.setComponentEnabledSetting] is the standard mechanism
 * every app offering icon variants uses. Exactly one alias is enabled at a time, and all six
 * target the same `MainActivity`, so switching never creates a second Activity instance.
 *
 * [PackageManager] is the sole source of truth for which icon is active — deliberately not
 * mirrored into `SettingsStore`. A handful of [PackageManager.getComponentEnabledSetting] calls
 * on a settings screen is cheap, and a mirrored key would only add a second source of truth that
 * needs reconciling against the real one for no real benefit.
 */
object AppIconManager {

    enum class AppIcon(
        val id: String,
        val label: String,
        private val aliasSuffix: String,
        val previewForeground: Int,
        // The same two gradient stops as this variant's background vector drawable, duplicated as
        // plain ints so the settings preview paints the gradient directly instead of depending on
        // vector <aapt:attr> gradient rendering. MINIMAL is the deliberate flat variant, so both
        // stops are equal. Keep in sync with res/drawable/ic_launcher_background*.xml.
        val previewTopColor: Long,
        val previewBottomColor: Long,
        // Whether AndroidManifest.xml declares this alias's own android:enabled as true — the
        // value PackageManager falls back to for COMPONENT_ENABLED_STATE_DEFAULT, i.e. before
        // this class has ever touched it. Only NAVY's is true; keep this in sync with the manifest.
        private val manifestDefaultEnabled: Boolean,
    ) {
        NAVY("navy", "Navy", "AppIconNavy", R.mipmap.ic_launcher_fg, 0xFF26426E, 0xFF0D1A33, true),
        MIDNIGHT("midnight", "Midnight", "AppIconMidnight", R.mipmap.ic_launcher_fg, 0xFF15151C, 0xFF030308, false),
        OCEAN("ocean", "Ocean", "AppIconOcean", R.mipmap.ic_launcher_fg, 0xFF0E8A8F, 0xFF04393B, false),
        EMBER("ember", "Ember", "AppIconEmber", R.mipmap.ic_launcher_fg, 0xFFB4432A, 0xFF4A160C, false),
        PLUM("plum", "Plum", "AppIconPlum", R.mipmap.ic_launcher_fg, 0xFF6E3480, 0xFF2C1236, false),
        MINIMAL("minimal", "Minimal", "AppIconMinimal", R.mipmap.ic_launcher_fg, 0xFF23262E, 0xFF23262E, false);

        internal fun componentName(context: Context): ComponentName =
            ComponentName(context.packageName, "com.betteraudio.$aliasSuffix")

        internal fun manifestDefault(): Boolean = manifestDefaultEnabled
    }

    private fun isEnabled(context: Context, icon: AppIcon): Boolean {
        val state = context.packageManager.getComponentEnabledSetting(icon.componentName(context))
        return when (state) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
            else -> icon.manifestDefault()   // COMPONENT_ENABLED_STATE_DEFAULT
        }
    }

    /** The currently active icon, read live from PackageManager. Falls back to [AppIcon.NAVY] in
     *  the (should-be-impossible) case that none read as enabled — better than crashing a settings
     *  screen over it. */
    fun current(context: Context): AppIcon =
        AppIcon.entries.firstOrNull { isEnabled(context, it) } ?: AppIcon.NAVY

    /**
     * Switches the launcher icon to [icon].
     *
     * [PackageManager.setComponentEnabledSetting] **kills the calling process** unless
     * [PackageManager.DONT_KILL_APP] is passed — every call below passes it, and all six
     * component states are written before this function returns, deliberately never letting the
     * OS end the process mid-sequence. Enable-the-target-then-disable-the-rest (or the reverse)
     * without that flag risks the process dying partway through: enable-first leaves **two**
     * launcher icons if the disables never run; disable-first leaves **zero**, i.e. the app
     * disappears from the launcher entirely. The caller decides separately, on its own terms,
     * whether and when to actually end the process afterwards (see Settings' confirm dialog,
     * which saves the current playback position first).
     */
    suspend fun apply(context: Context, icon: AppIcon) = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        for (candidate in AppIcon.entries) {
            val state = if (candidate == icon) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            pm.setComponentEnabledSetting(candidate.componentName(context), state, PackageManager.DONT_KILL_APP)
        }
        AppLog.i(LogCat.UI, "AppIconManager: switched to ${icon.id}")
    }
}
