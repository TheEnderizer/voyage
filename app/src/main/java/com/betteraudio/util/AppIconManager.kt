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
 * every app offering icon variants uses. Exactly one alias is enabled at a time, and they all
 * target the same `MainActivity`, so switching never creates a second Activity instance.
 *
 * [PackageManager] is the sole source of truth for which icon is active — deliberately not
 * mirrored into `SettingsStore`. A handful of [PackageManager.getComponentEnabledSetting] calls
 * on a settings screen is cheap, and a mirrored key would only add a second source of truth that
 * needs reconciling against the real one for no real benefit.
 */
object AppIconManager {

    /**
     * The three marks. Every icon is one of these drawn in one colourway, which is how the picker
     * presents them: choose a style, then a colour, instead of scrolling one flat strip of sixteen.
     */
    enum class IconStyle(val label: String) {
        VEE("Vee"),
        VOYAGER("Voyager"),
        CLASSIC("Classic");

        /** This style's colourways, in [AppIcon] declaration order. */
        fun icons(): List<AppIcon> = AppIcon.entries.filter { it.style == this }

        /** What the style's own tile shows when the active icon belongs to a different style. */
        fun defaultIcon(): AppIcon = icons().first()
    }

    enum class AppIcon(
        val id: String,
        val style: IconStyle,
        /** Colour name alone — the style supplies the rest, so the picker can label a swatch
         *  "Sunset" rather than repeating "Vee" under all five of them. */
        val colorLabel: String,
        private val aliasSuffix: String,
        // The six sailboat variants share one mark and differ only by background gradient. The
        // VEE family and VOYAGER are whole authored icons — ground included — so each brings
        // its own foreground, which is why this is a per-entry field rather than a constant.
        val previewForeground: Int,
        // The same two gradient stops as this variant's background vector drawable, duplicated as
        // plain ints so the settings preview paints the gradient directly instead of depending on
        // vector <aapt:attr> gradient rendering. MINIMAL is the deliberate flat variant, so both
        // stops are equal. Keep in sync with res/drawable/ic_launcher_background*.xml (the
        // generator prints them).
        val previewTopColor: Long,
        val previewBottomColor: Long,
        // Whether AndroidManifest.xml declares this alias's own android:enabled as true — the
        // value PackageManager falls back to for COMPONENT_ENABLED_STATE_DEFAULT, i.e. before
        // this class has ever touched it. Exactly one entry's is true (VEE, the default a fresh
        // install starts on); keep this in sync with the manifest.
        private val manifestDefaultEnabled: Boolean,
    ) {
        // Declaration order groups the styles and orders the colourways inside each — see
        // IconStyle.icons(), which filters this list rather than keeping a second one. Within
        // a style the authored gradient colourways come first and the flat ones after, so the
        // picker's wrapping grid reads as two rows of one treatment each.
        VEE("vee", IconStyle.VEE, "Aurora", "AppIconVee", R.mipmap.ic_launcher_fg_vee, 0xFF06102B, 0xFF030614, true),
        VEE_SUNSET("vee_sunset", IconStyle.VEE, "Sunset", "AppIconVeeSunset", R.mipmap.ic_launcher_fg_vee_sunset, 0xFF2A0710, 0xFF140306, false),
        VEE_ORCHID("vee_orchid", IconStyle.VEE, "Orchid", "AppIconVeeOrchid", R.mipmap.ic_launcher_fg_vee_orchid, 0xFF1E072A, 0xFF0F0215, false),
        VEE_MEADOW("vee_meadow", IconStyle.VEE, "Meadow", "AppIconVeeMeadow", R.mipmap.ic_launcher_fg_vee_meadow, 0xFF082A23, 0xFF021513, false),
        VEE_STEEL("vee_steel", IconStyle.VEE, "Steel", "AppIconVeeSteel", R.mipmap.ic_launcher_fg_vee_steel, 0xFF25262A, 0xFF121315, false),
        VEE_FLAT_AURORA("vee_flat_aurora", IconStyle.VEE, "Flat aurora", "AppIconVeeFlatAurora", R.mipmap.ic_launcher_fg_vee_flat_aurora, 0xFF2A46C8, 0xFF2A46C8, false),
        VEE_FLAT_SUNSET("vee_flat_sunset", IconStyle.VEE, "Flat sunset", "AppIconVeeFlatSunset", R.mipmap.ic_launcher_fg_vee_flat_sunset, 0xFFD8452C, 0xFFD8452C, false),
        VEE_FLAT_ORCHID("vee_flat_orchid", IconStyle.VEE, "Flat orchid", "AppIconVeeFlatOrchid", R.mipmap.ic_launcher_fg_vee_flat_orchid, 0xFF7A34C0, 0xFF7A34C0, false),
        VEE_FLAT_MEADOW("vee_flat_meadow", IconStyle.VEE, "Flat meadow", "AppIconVeeFlatMeadow", R.mipmap.ic_launcher_fg_vee_flat_meadow, 0xFF158A5C, 0xFF158A5C, false),
        VEE_FLAT_PAPER("vee_flat_paper", IconStyle.VEE, "Flat paper", "AppIconVeeFlatPaper", R.mipmap.ic_launcher_fg_vee_flat_paper, 0xFFECEEF2, 0xFFECEEF2, false),
        VOYAGER("voyager", IconStyle.VOYAGER, "Tide", "AppIconVoyager", R.mipmap.ic_launcher_fg_voyager, 0xFF0C2549, 0xFF01091E, false),
        VOYAGER_ROSE("voyager_rose", IconStyle.VOYAGER, "Rose", "AppIconVoyagerRose", R.mipmap.ic_launcher_fg_voyager_rose, 0xFF490C26, 0xFF1E0109, false),
        VOYAGER_INDIGO("voyager_indigo", IconStyle.VOYAGER, "Indigo", "AppIconVoyagerIndigo", R.mipmap.ic_launcher_fg_voyager_indigo, 0xFF290C49, 0xFF12011E, false),
        VOYAGER_FERN("voyager_fern", IconStyle.VOYAGER, "Fern", "AppIconVoyagerFern", R.mipmap.ic_launcher_fg_voyager_fern, 0xFF0C4933, 0xFF011E17, false),
        VOYAGER_PEARL("voyager_pearl", IconStyle.VOYAGER, "Pearl", "AppIconVoyagerPearl", R.mipmap.ic_launcher_fg_voyager_pearl, 0xFF3F4349, 0xFF191A1D, false),
        VOYAGER_FLAT_AURORA("voyager_flat_aurora", IconStyle.VOYAGER, "Flat tide", "AppIconVoyagerFlatAurora", R.mipmap.ic_launcher_fg_voyager_flat_aurora, 0xFF2A46C8, 0xFF2A46C8, false),
        VOYAGER_FLAT_SUNSET("voyager_flat_sunset", IconStyle.VOYAGER, "Flat rose", "AppIconVoyagerFlatSunset", R.mipmap.ic_launcher_fg_voyager_flat_sunset, 0xFFD8452C, 0xFFD8452C, false),
        VOYAGER_FLAT_ORCHID("voyager_flat_orchid", IconStyle.VOYAGER, "Flat indigo", "AppIconVoyagerFlatOrchid", R.mipmap.ic_launcher_fg_voyager_flat_orchid, 0xFF7A34C0, 0xFF7A34C0, false),
        VOYAGER_FLAT_MEADOW("voyager_flat_meadow", IconStyle.VOYAGER, "Flat fern", "AppIconVoyagerFlatMeadow", R.mipmap.ic_launcher_fg_voyager_flat_meadow, 0xFF158A5C, 0xFF158A5C, false),
        VOYAGER_FLAT_PAPER("voyager_flat_paper", IconStyle.VOYAGER, "Flat pearl", "AppIconVoyagerFlatPaper", R.mipmap.ic_launcher_fg_voyager_flat_paper, 0xFFECEEF2, 0xFFECEEF2, false),
        NAVY("navy", IconStyle.CLASSIC, "Navy", "AppIconNavy", R.mipmap.ic_launcher_fg, 0xFF26426E, 0xFF0D1A33, false),
        MIDNIGHT("midnight", IconStyle.CLASSIC, "Midnight", "AppIconMidnight", R.mipmap.ic_launcher_fg, 0xFF15151C, 0xFF030308, false),
        OCEAN("ocean", IconStyle.CLASSIC, "Ocean", "AppIconOcean", R.mipmap.ic_launcher_fg, 0xFF0E8A8F, 0xFF04393B, false),
        EMBER("ember", IconStyle.CLASSIC, "Ember", "AppIconEmber", R.mipmap.ic_launcher_fg, 0xFFB4432A, 0xFF4A160C, false),
        PLUM("plum", IconStyle.CLASSIC, "Plum", "AppIconPlum", R.mipmap.ic_launcher_fg, 0xFF6E3480, 0xFF2C1236, false),
        MINIMAL("minimal", IconStyle.CLASSIC, "Minimal", "AppIconMinimal", R.mipmap.ic_launcher_fg, 0xFF23262E, 0xFF23262E, false);

        /** Style plus colour — what a confirm dialog needs to name one icon unambiguously. */
        val label: String get() = "${style.label} ${colorLabel}"

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

    /** The currently active icon, read live from PackageManager. Prefers an alias the user
     *  explicitly turned on over one that is merely enabled by manifest default, so a build that
     *  moves the default never reports over the top of a choice the user already made. Falls back
     *  to [AppIcon.VEE] if somehow none read as enabled — better than crashing a settings screen
     *  over it. */
    fun current(context: Context): AppIcon =
        AppIcon.entries.firstOrNull { explicitlyEnabled(context, it) }
            ?: AppIcon.entries.firstOrNull { isEnabled(context, it) }
            ?: AppIcon.VEE

    private fun explicitlyEnabled(context: Context, icon: AppIcon): Boolean =
        context.packageManager.getComponentEnabledSetting(icon.componentName(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /**
     * Forces exactly one launcher alias on, and does nothing when that is already true.
     *
     * Moving the manifest default (NAVY -> VEE) is not a purely additive change: on an install
     * where the user had explicitly chosen, say, PLUM, that alias is COMPONENT_ENABLED_STATE_ENABLED
     * while the newly-defaulted VEE is still COMPONENT_ENABLED_STATE_DEFAULT — which now resolves
     * to true. Both are enabled, and the launcher shows the app twice. Aliases added in a later
     * version are invisible to the [apply] call that ran before they existed, so this cannot be
     * fixed at the point the user picks an icon; it has to be reconciled on launch.
     *
     * Cheap enough to run every start (a handful of PackageManager reads) and silent unless there
     * is genuinely a conflict.
     */
    suspend fun reconcile(context: Context) {
        val enabled = AppIcon.entries.filter { isEnabled(context, it) }
        if (enabled.size <= 1) return
        val keep = enabled.firstOrNull { explicitlyEnabled(context, it) } ?: enabled.first()
        AppLog.w(LogCat.UI, "AppIconManager: ${enabled.size} launcher aliases enabled " +
            "(${enabled.joinToString { it.id }}) — collapsing to ${keep.id}")
        apply(context, keep)
    }

    /**
     * Switches the launcher icon to [icon].
     *
     * [PackageManager.setComponentEnabledSetting] **kills the calling process** unless
     * [PackageManager.DONT_KILL_APP] is passed — every call below passes it, and every
     * component state is written before this function returns, deliberately never letting the
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
