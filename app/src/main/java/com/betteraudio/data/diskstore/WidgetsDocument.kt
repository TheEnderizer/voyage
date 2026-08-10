package com.betteraudio.data.diskstore

/**
 * In-memory shape of `<libraryRoot>/.voyage/widgets/designs.json` — every saved [WidgetDesign]
 * row, keyed by name on restore the same way [com.betteraudio.widget.DefaultWidgetDesigns.ensureSeeded]
 * already does, so re-running a restore never duplicates a design. `WidgetBinding` (which placed
 * home-screen widget uses which design) is never written — `appWidgetId` is launcher-assigned and
 * ephemeral, meaningless on a different install.
 */
data class WidgetsDocument(
    val version: Int = CURRENT_VERSION,
    val writtenAt: Long,
    val designs: List<DesignEntry> = emptyList(),
    val unknown: Map<String, Any?> = emptyMap()
) {
    data class DesignEntry(
        val name: String,
        val aspectRatio: Float,
        /** Opaque WidgetDesignDoc JSON (see WidgetDesignCodec) — element image paths inside it are
         *  absolute `filesDir/widget_images/<uuid>.jpg` paths, valid again once [images] restores
         *  those exact files under those exact names; never rewritten here. */
        val documentJson: String,
        val createdAt: Long,
        val updatedAt: Long,
        val unknown: Map<String, Any?> = emptyMap()
    )

    companion object {
        const val CURRENT_VERSION = 1
    }
}
