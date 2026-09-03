package com.betteraudio.data.diskstore

import java.io.File

/** Pure path derivation for the library-root `.voyage/` folder — settings.json, library.json,
 *  covers/, widgets/. All null when [libraryFolder] is blank (nothing chosen yet). */
object VoyageLayout {
    const val ROOT_DIR_NAME = ".voyage"
    const val LIBRARY_FILE_NAME = "library.json"
    const val SETTINGS_FILE_NAME = "settings.json"
    const val COVERS_DIR_NAME = "covers"
    const val WIDGETS_DIR_NAME = "widgets"
    const val WIDGET_DESIGNS_FILE_NAME = "designs.json"
    const val WIDGET_IMAGES_DIR_NAME = "images"
    /** Companion packs (docs/companion-packs.md §7.1). Series-scoped packs only — a BOOK-scoped
     *  pack lives under the book's own `data/` dir instead, see [BookDataPaths.companionDir]. */
    const val COMPANION_DIR_NAME = "companion"

    fun rootDir(libraryFolder: String): File? = libraryFolder.takeIf { it.isNotBlank() }?.let { File(it, ROOT_DIR_NAME) }
    fun libraryFile(libraryFolder: String): File? = rootDir(libraryFolder)?.let { File(it, LIBRARY_FILE_NAME) }
    fun settingsFile(libraryFolder: String): File? = rootDir(libraryFolder)?.let { File(it, SETTINGS_FILE_NAME) }
    fun coversDir(libraryFolder: String): File? = rootDir(libraryFolder)?.let { File(it, COVERS_DIR_NAME) }
    fun widgetsDir(libraryFolder: String): File? = rootDir(libraryFolder)?.let { File(it, WIDGETS_DIR_NAME) }
    fun widgetDesignsFile(libraryFolder: String): File? = widgetsDir(libraryFolder)?.let { File(it, WIDGET_DESIGNS_FILE_NAME) }
    fun widgetImagesDir(libraryFolder: String): File? = widgetsDir(libraryFolder)?.let { File(it, WIDGET_IMAGES_DIR_NAME) }

    /** Root of every SERIES-scoped companion pack — `.voyage/companion/`. Packs live one level
     *  down, `<this>/<packId>/`, keyed by [com.betteraudio.data.db.entities.CompanionPack.packId],
     *  not by series name (several packs can target the same series). */
    fun companionRootDir(libraryFolder: String): File? = rootDir(libraryFolder)?.let { File(it, COMPANION_DIR_NAME) }

    /** `.voyage/companion/<packId>/` for a SERIES-scoped pack. */
    fun seriesPackDir(libraryFolder: String, packId: String): File? =
        companionRootDir(libraryFolder)?.let { File(it, packId) }
}
