package com.betteraudio.widget

import com.betteraudio.data.db.dao.WidgetDesignDao
import com.betteraudio.data.db.entities.WidgetDesign
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import com.betteraudio.widget.model.BackgroundLayerStyle
import com.betteraudio.widget.model.BgSource
import com.betteraudio.widget.model.CANVAS_UNITS
import com.betteraudio.widget.model.ContainerShape
import com.betteraudio.widget.model.ElementSpec
import com.betteraudio.widget.model.ElementType
import com.betteraudio.widget.model.IconStyle
import com.betteraudio.widget.model.ProgressShape
import com.betteraudio.widget.model.ShapeStyle
import com.betteraudio.widget.model.TextStyle
import com.betteraudio.widget.model.WidgetDesignCodec
import com.betteraudio.widget.model.WidgetDesignDoc

/**
 * The two out-of-the-box widget designs — each has its own fixed [VoyageWidgetProviderCoverControls]/
 * [VoyageWidgetProviderMinimalBar] launcher-picker entry (see AndroidManifest.xml), auto-bound to
 * the design seeded here by [ensureSeeded] the first time either is placed (or on first app launch
 * — [ensureSeeded] is idempotent, looked up by these stable [NAME_COVER_CONTROLS]/[NAME_MINIMAL_BAR]
 * names). The freeform "Custom" provider (the original [VoyageWidgetProvider]) is unaffected — it
 * still opens [WidgetConfigureActivity] to pick any user-designed WidgetDesign.
 */
object DefaultWidgetDesigns {
    const val NAME_COVER_CONTROLS = "Cover & Controls"
    const val NAME_MINIMAL_BAR = "Minimal Bar"

    /** Upserts both default designs if they don't already exist (matched by stable name) — safe to
     *  call repeatedly (WidgetUpdater does, once per process, before anything renders). */
    suspend fun ensureSeeded(designDao: WidgetDesignDao) {
        try {
            if (designDao.getByName(NAME_COVER_CONTROLS) == null) {
                designDao.upsert(
                    WidgetDesign(
                        name = NAME_COVER_CONTROLS,
                        aspectRatio = ASPECT_COVER_CONTROLS,
                        documentJson = WidgetDesignCodec.encode(coverControlsDoc(ASPECT_COVER_CONTROLS))
                    )
                )
            }
            if (designDao.getByName(NAME_MINIMAL_BAR) == null) {
                designDao.upsert(
                    WidgetDesign(
                        name = NAME_MINIMAL_BAR,
                        aspectRatio = ASPECT_MINIMAL_BAR,
                        documentJson = WidgetDesignCodec.encode(minimalBarDoc(ASPECT_MINIMAL_BAR))
                    )
                )
            }
        } catch (e: Exception) {
            AppLog.e(LogCat.WIDGET, "seeding default designs failed", e)
        }
    }

    // "Large" (4:3) and "Banner" (4:1) from WIDGET_SIZE_PRESETS — kept as plain constants here
    // rather than importing ui/widget/WidgetSizePresets.kt, which is an editor-UI file.
    const val ASPECT_COVER_CONTROLS = 4f / 3f
    const val ASPECT_MINIMAL_BAR = 4f

    /** Full-bleed cover with title/author/progress and transport, dim'd for legibility. */
    fun coverControlsDoc(aspectRatio: Float = ASPECT_COVER_CONTROLS): WidgetDesignDoc {
        val canvasH = CANVAS_UNITS / aspectRatio
        val controlsY = canvasH - 150f
        val progressY = controlsY - 40f
        val authorY = progressY - 70f
        val titleY = authorY - 85f
        val cx = CANVAS_UNITS / 2f
        return WidgetDesignDoc(
            elements = listOf(
                ElementSpec(
                    type = ElementType.BACKGROUND_LAYER, x = 0f, y = 0f, w = CANVAS_UNITS, h = canvasH,
                    backgroundLayer = BackgroundLayerStyle(source = BgSource.BOOK_COVER, dim = 0.35f, cornerRadius = 60f)
                ),
                ElementSpec(
                    type = ElementType.BOOK_TITLE, x = 40f, y = titleY, w = CANVAS_UNITS - 80f, h = 75f,
                    text = TextStyle(sizeUnits = 58f, weight = 700, color = 0xFFFFFFFF, maxLines = 2)
                ),
                ElementSpec(
                    type = ElementType.AUTHOR, x = 40f, y = authorY, w = CANVAS_UNITS - 80f, h = 50f,
                    text = TextStyle(sizeUnits = 36f, weight = 500, color = 0xCCFFFFFF)
                ),
                ElementSpec(
                    type = ElementType.PROGRESS_BAR, x = 40f, y = progressY, w = CANVAS_UNITS - 80f, h = 14f,
                    shape = ShapeStyle(progressShape = ProgressShape.LINE)
                ),
                ElementSpec(
                    type = ElementType.SKIP_BACK, x = cx - 200f, y = controlsY, w = 100f, h = 100f,
                    icon = IconStyle(glyphColor = 0xFFFFFFFF, glyphUsesAccent = false)
                ),
                ElementSpec(
                    type = ElementType.PLAY_PAUSE, x = cx - 55f, y = controlsY - 5f, w = 110f, h = 110f,
                    icon = IconStyle(
                        container = ContainerShape.CIRCLE, containerUsesAccent = true,
                        glyphColor = 0xFFFFFFFF, glyphUsesAccent = false
                    )
                ),
                ElementSpec(
                    type = ElementType.SKIP_FORWARD, x = cx + 100f, y = controlsY, w = 100f, h = 100f,
                    icon = IconStyle(glyphColor = 0xFFFFFFFF, glyphUsesAccent = false)
                ),
            )
        )
    }

    /** A slim, cover-free bar: title/author on the left, transport on the right. */
    fun minimalBarDoc(aspectRatio: Float = ASPECT_MINIMAL_BAR): WidgetDesignDoc {
        val canvasH = CANVAS_UNITS / aspectRatio
        return WidgetDesignDoc(
            elements = listOf(
                ElementSpec(
                    type = ElementType.BACKGROUND_LAYER, x = 0f, y = 0f, w = CANVAS_UNITS, h = canvasH,
                    backgroundLayer = BackgroundLayerStyle(source = BgSource.SOLID, color = 0xFF1A1A22, cornerRadius = 40f)
                ),
                ElementSpec(
                    type = ElementType.BOOK_TITLE, x = 40f, y = canvasH * 0.18f, w = 560f, h = canvasH * 0.4f,
                    text = TextStyle(sizeUnits = 46f, weight = 700, color = 0xFFFFFFFF)
                ),
                ElementSpec(
                    type = ElementType.AUTHOR, x = 40f, y = canvasH * 0.58f, w = 560f, h = canvasH * 0.3f,
                    text = TextStyle(sizeUnits = 28f, weight = 500, color = 0xCCFFFFFF)
                ),
                ElementSpec(
                    type = ElementType.SKIP_BACK, x = 660f, y = canvasH / 2f - 45f, w = 90f, h = 90f,
                    icon = IconStyle(glyphUsesAccent = true)
                ),
                ElementSpec(
                    type = ElementType.PLAY_PAUSE, x = 770f, y = canvasH / 2f - 60f, w = 120f, h = 120f,
                    icon = IconStyle(
                        container = ContainerShape.CIRCLE, containerUsesAccent = true,
                        glyphColor = 0xFFFFFFFF, glyphUsesAccent = false
                    )
                ),
                ElementSpec(
                    type = ElementType.SKIP_FORWARD, x = 910f, y = canvasH / 2f - 45f, w = 90f, h = 90f,
                    icon = IconStyle(glyphUsesAccent = true)
                ),
            )
        )
    }
}
