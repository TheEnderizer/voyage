package com.betteraudio.ui.widget

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.betteraudio.R
import com.betteraudio.widget.WidgetState
import com.betteraudio.widget.custom.CustomWidgetRenderer
import com.betteraudio.widget.custom.WidgetBackground
import com.betteraudio.widget.custom.WidgetElement
import com.betteraudio.widget.custom.WidgetElementType
import com.betteraudio.widget.custom.WidgetSizeBucket
import com.betteraudio.widget.custom.effectiveRect
import kotlin.math.roundToInt

private val SAMPLE_STATE = WidgetState(
    title = "Sample Book Title",
    author = "Sample Author",
    isPlaying = true,
    chapterTitle = "Chapter 3",
    seriesName = "Sample Series",
    speed = 1.2f,
    boostDb = 3
)

@Composable
fun WidgetEditorScreen(onBack: () -> Unit, viewModel: WidgetEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) { if (state.saved) onBack() }

    if (!state.loaded) return

    if (!state.sizeChosen) {
        SizePickerStep(onPick = viewModel::chooseSize, onBack = onBack)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = state.name,
                        onValueChange = viewModel::setName,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    TextButton(onClick = viewModel::save) { Text("Save") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            WidgetCanvas(state = state, viewModel = viewModel, modifier = Modifier.fillMaxWidth())

            Spacer(Modifier.height(16.dp))

            var showAddSheet by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showAddSheet = true }) { Text("Add element") }
                if (state.selectedIndex != -1) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { viewModel.resizeSelected(grow = false) }) {
                            Icon(Icons.Default.Remove, "Shrink element")
                        }
                        IconButton(onClick = { viewModel.resizeSelected(grow = true) }) {
                            Icon(Icons.Default.Add, "Grow element")
                        }
                    }
                    IconButton(onClick = viewModel::deleteSelected) {
                        Icon(Icons.Default.Delete, "Delete element", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            BackgroundPicker(state = state, viewModel = viewModel)

            if (state.selectedIndex in state.elements.indices) {
                Spacer(Modifier.height(16.dp))
                ElementPropertyPanel(
                    element = state.elements[state.selectedIndex],
                    index = state.selectedIndex,
                    viewModel = viewModel
                )
            }

            if (showAddSheet) {
                AddElementSheet(
                    onPick = { type -> viewModel.addElement(type); showAddSheet = false },
                    onDismiss = { showAddSheet = false }
                )
            }
        }
    }
}

@Composable
private fun SizePickerStep(onPick: (WidgetSizeBucket) -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choose a size") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            WidgetSizeBucket.entries.forEach { bucket ->
                Surface(
                    onClick = { onPick(bucket) },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(bucket.name.lowercase().replaceFirstChar { it.uppercase() })
                        Text("${bucket.cellsW}×${bucket.cellsH}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * Live Compose preview of the design being edited — no widget bitmap is rendered here at all
 * (that only happens for the real home-screen widget, via [CustomWidgetRenderer]). Background and
 * elements are drawn as plain Compose content ("just images"/icons/text) positioned from their
 * normalized rects, so dragging/selecting is real layout, not an invisible overlay atop a
 * per-frame-rendered bitmap.
 */
@Composable
private fun WidgetCanvas(state: WidgetEditorState, viewModel: WidgetEditorViewModel, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val bucket = state.sizeBucket
    val aspect = bucket.cellsW.toFloat() / bucket.cellsH.toFloat()
    val accent = MaterialTheme.colorScheme.primary
    val lastPlayedCoverPath by viewModel.lastPlayedCoverPath.collectAsStateWithLifecycle()

    var canvasWidthPx by remember { mutableStateOf(0) }
    var canvasHeightPx by remember { mutableStateOf(0) }

    Box(
        modifier
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(20.dp))
            .onSizeChanged(density) { w, h -> canvasWidthPx = w; canvasHeightPx = h }
    ) {
        WidgetBackgroundPreview(state.background, state.backgroundValue, accent, lastPlayedCoverPath)

        val w = canvasWidthPx
        val h = canvasHeightPx
        if (w > 0 && h > 0) {
            state.elements.forEachIndexed { index, el ->
                ElementPreview(
                    index = index,
                    element = el,
                    canvasWidthPx = w,
                    canvasHeightPx = h,
                    accent = accent,
                    selected = index == state.selectedIndex,
                    onSelect = { viewModel.select(index) },
                    onDragDelta = { dxFrac, dyFrac -> viewModel.moveElement(index, dxFrac, dyFrac) },
                    onDragEnd = { viewModel.snapPosition(index) }
                )
            }
        }
    }
}

@Composable
private fun BoxScope.WidgetBackgroundPreview(
    background: WidgetBackground,
    backgroundValue: String,
    accent: Color,
    lastPlayedCoverPath: String?
) {
    when (background) {
        WidgetBackground.CUSTOM_IMAGE -> coil.compose.AsyncImage(
            model = backgroundValue.takeIf { it.isNotBlank() }?.let { java.io.File(it) },
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceContainerHigh)
        )
        WidgetBackground.CUSTOM_COLOR -> Box(
            Modifier.matchParentSize().background(parsePreviewColor(backgroundValue, accent))
        )
        // Matches WidgetRender.darkTint(appColor) — the real widget's APP_COLOR fill is this dark,
        // accent-tinted tone, not the raw bright primary color.
        WidgetBackground.APP_COLOR -> Box(
            Modifier.matchParentSize().background(previewDarkTint(accent))
        )
        // BOOK_COVER / SERIES_COVER: show the last-played book's actual cover (same fallback the
        // real widget uses when nothing is currently playing) so the preview reflects reality
        // instead of a generic placeholder forever; only falls back to that placeholder when
        // nothing has ever played.
        WidgetBackground.BOOK_COVER, WidgetBackground.SERIES_COVER -> {
            if (lastPlayedCoverPath != null) {
                coil.compose.AsyncImage(
                    model = java.io.File(lastPlayedCoverPath),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceContainerHigh)
                )
            } else {
                Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceContainerHigh))
            }
        }
    }
}

/** Compose equivalent of [com.betteraudio.widget.WidgetRender.darkTint] — kept in sync by formula,
 *  not a shared call, since one works on Android [Int] colors and the other on Compose [Color]. */
private fun previewDarkTint(accent: Color): Color {
    val r = (accent.red * 255f * 0.16f + 12f).coerceIn(0f, 255f) / 255f
    val g = (accent.green * 255f * 0.16f + 11f).coerceIn(0f, 255f) / 255f
    val b = (accent.blue * 255f * 0.16f + 11f).coerceIn(0f, 255f) / 255f
    return Color(r, g, b)
}

private fun parsePreviewColor(hex: String, fallback: Color): Color =
    if (hex.isBlank()) fallback
    else runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)

/** Reports the composable's measured pixel size once known (used to place elements). */
private fun Modifier.onSizeChanged(density: androidx.compose.ui.unit.Density, onSize: (Int, Int) -> Unit): Modifier =
    this.then(Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        onSize(placeable.width, placeable.height)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    })

@Composable
private fun BoxScope.ElementPreview(
    index: Int,
    element: WidgetElement,
    canvasWidthPx: Int,
    canvasHeightPx: Int,
    accent: Color,
    selected: Boolean,
    onSelect: () -> Unit,
    onDragDelta: (dxFrac: Float, dyFrac: Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val density = LocalDensity.current
    // Effective (aspect-corrected, icon-square) rect — matches the real widget's render and tap
    // mapping exactly (see WidgetElement.effectiveRect), so an icon added by an older app version
    // (a larger, non-square legacy footprint) previews and drags identically to how it now
    // actually behaves on the home screen, not the stale raw stored rect.
    val eff = remember(element) { element.effectiveRect(canvasWidthPx.toFloat() / canvasHeightPx) }
    val leftPx = eff[0] * canvasWidthPx
    val topPx = eff[1] * canvasHeightPx
    val wPx = (eff[2] * canvasWidthPx).coerceAtLeast(8f)
    val hPx = (eff[3] * canvasHeightPx).coerceAtLeast(8f)
    // Matches CustomWidgetRenderer.drawImageRect's radius exactly (min(w,h) * 0.12f) so the
    // editor preview's rounding matches what the real widget will actually render.
    val imageRadius = with(density) { (minOf(wPx, hPx) * 0.12f).toDp() }
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    // Icon-type (interactive) elements are given a square w/h by WidgetEditorViewModel (aspect-
    // corrected per bucket, see WidgetElement.aspect) precisely so this box — which carries the
    // border AND the click/drag target — already equals the icon's own footprint with no extra
    // padding: no separate inner box sized to "just the icon" is needed or correct anymore, since
    // the element itself now IS just the size of the icon.
    val isIconElement = element.type.isInteractive

    Box(
        Modifier
            .offset { androidx.compose.ui.unit.IntOffset(leftPx.roundToInt(), topPx.roundToInt()) }
            .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
            .then(
                if (isIconElement) Modifier.border(2.dp, borderColor, RoundedCornerShape(8.dp))
                else Modifier
            )
            .clickable { onSelect() }
            // Keyed on the element's stable slot (its index in the list), NOT the element value —
            // keying on the value restarted this gesture coroutine every drag delta (the element
            // changes every frame while dragging), which is why moving used to not work at all.
            .pointerInput(index) {
                detectDragGestures(
                    onDragStart = { onSelect() },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() }
                ) { change, dragAmount ->
                    change.consume()
                    onDragDelta(dragAmount.x / canvasWidthPx, dragAmount.y / canvasHeightPx)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            element.type.isText -> Text(
                textForPreview(element.type),
                color = Color.White,
                fontSize = androidx.compose.ui.unit.TextUnit(
                    element.fontSizeSp ?: 14f, androidx.compose.ui.unit.TextUnitType.Sp
                ),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.border(2.dp, borderColor, RoundedCornerShape(4.dp))
            )
            element.type == WidgetElementType.CUSTOM_IMAGE -> {
                if (element.imagePath != null) {
                    coil.compose.AsyncImage(
                        model = java.io.File(element.imagePath),
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(imageRadius))
                            .border(2.dp, borderColor, RoundedCornerShape(imageRadius))
                    )
                } else {
                    ImagePlaceholder(radius = imageRadius, borderColor = borderColor)
                }
            }
            element.type == WidgetElementType.BOOK_COVER || element.type == WidgetElementType.SERIES_COVER ->
                ImagePlaceholder(radius = imageRadius, borderColor = borderColor)
            // Matches CustomWidgetRenderer.drawIcon, which draws the icon at min(rect.width,
            // rect.height) centered. That min() is now a no-op in practice (wPx == hPx already,
            // by construction) — the border lives on the outer box above, not a nested one, since
            // the outer box no longer has any padding around the icon to hide.
            else -> ElementTypeIcon(
                element.type,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(0.9f),
                tint = accent
            )
        }
    }
}

@Composable
private fun ImagePlaceholder(radius: androidx.compose.ui.unit.Dp, borderColor: Color = Color.Transparent) {
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(radius))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(2.dp, borderColor, RoundedCornerShape(radius)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Image, null,
            Modifier.fillMaxSize(0.5f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private val ELEMENT_CATEGORIES: List<Pair<String, List<WidgetElementType>>> = listOf(
    "Playback" to listOf(
        WidgetElementType.PLAY_PAUSE, WidgetElementType.SKIP_FORWARD, WidgetElementType.SKIP_BACK,
        WidgetElementType.CHAPTER_FORWARD, WidgetElementType.CHAPTER_BACK
    ),
    "Audio" to listOf(
        WidgetElementType.SPEED_UP, WidgetElementType.SPEED_DOWN,
        WidgetElementType.BOOST_UP, WidgetElementType.BOOST_DOWN
    ),
    "Tools" to listOf(
        WidgetElementType.SLEEP_TIMER, WidgetElementType.QUICK_BOOKMARK, WidgetElementType.CLOSE_BOOK
    ),
    "Text" to listOf(
        WidgetElementType.BOOK_NAME, WidgetElementType.AUTHOR_NAME,
        WidgetElementType.CHAPTER_NAME, WidgetElementType.SERIES_NAME
    ),
    "Images" to listOf(
        WidgetElementType.BOOK_COVER, WidgetElementType.SERIES_COVER, WidgetElementType.CUSTOM_IMAGE
    )
)

@Composable
private fun AddElementSheet(onPick: (WidgetElementType) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(max = 520.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            ELEMENT_CATEGORIES.forEach { (category, types) ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(category, style = MaterialTheme.typography.titleSmall)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(types) { type ->
                                ElementTypeButton(type = type, onClick = { onPick(type) })
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun ElementTypeButton(type: WidgetElementType, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        ElementTypeIcon(type, contentDescription = type.name.replace('_', ' '), modifier = Modifier.size(24.dp))
    }
}

/** The same glyph the rendered widget uses for action elements; a Material icon otherwise. */
@Composable
private fun ElementTypeIcon(
    type: WidgetElementType,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    when (type) {
        WidgetElementType.PLAY_PAUSE -> Icon(painterResource(R.drawable.ic_play), contentDescription, modifier, tint)
        WidgetElementType.SKIP_FORWARD -> Icon(painterResource(R.drawable.ic_skip_forward), contentDescription, modifier, tint)
        WidgetElementType.SKIP_BACK -> Icon(painterResource(R.drawable.ic_skip_back), contentDescription, modifier, tint)
        WidgetElementType.CHAPTER_FORWARD -> Icon(painterResource(R.drawable.ic_chapter_forward), contentDescription, modifier, tint)
        WidgetElementType.CHAPTER_BACK -> Icon(painterResource(R.drawable.ic_chapter_back), contentDescription, modifier, tint)
        WidgetElementType.SPEED_UP -> Icon(painterResource(R.drawable.ic_speed_up), contentDescription, modifier, tint)
        WidgetElementType.SPEED_DOWN -> Icon(painterResource(R.drawable.ic_speed_down), contentDescription, modifier, tint)
        WidgetElementType.BOOST_UP -> Icon(painterResource(R.drawable.ic_boost_up), contentDescription, modifier, tint)
        WidgetElementType.BOOST_DOWN -> Icon(painterResource(R.drawable.ic_boost_down), contentDescription, modifier, tint)
        WidgetElementType.SLEEP_TIMER -> Icon(painterResource(R.drawable.ic_sleep), contentDescription, modifier, tint)
        WidgetElementType.QUICK_BOOKMARK -> Icon(painterResource(R.drawable.ic_bookmark_add), contentDescription, modifier, tint)
        WidgetElementType.CLOSE_BOOK -> Icon(painterResource(R.drawable.ic_close_book), contentDescription, modifier, tint)
        WidgetElementType.BOOK_NAME -> Icon(Icons.Default.Title, contentDescription, modifier, tint)
        WidgetElementType.AUTHOR_NAME -> Icon(Icons.Default.Person, contentDescription, modifier, tint)
        WidgetElementType.CHAPTER_NAME -> Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription, modifier, tint)
        WidgetElementType.SERIES_NAME -> Icon(Icons.Default.CollectionsBookmark, contentDescription, modifier, tint)
        WidgetElementType.BOOK_COVER -> Icon(Icons.Default.Image, contentDescription, modifier, tint)
        WidgetElementType.SERIES_COVER -> Icon(Icons.Default.PhotoLibrary, contentDescription, modifier, tint)
        WidgetElementType.CUSTOM_IMAGE -> Icon(Icons.Default.AddPhotoAlternate, contentDescription, modifier, tint)
    }
}

/** Sample text shown for a text element in the editor preview (see [SAMPLE_STATE]). */
private fun textForPreview(type: WidgetElementType): String = when (type) {
    WidgetElementType.BOOK_NAME -> SAMPLE_STATE.title
    WidgetElementType.AUTHOR_NAME -> SAMPLE_STATE.author
    WidgetElementType.CHAPTER_NAME -> SAMPLE_STATE.chapterTitle
    WidgetElementType.SERIES_NAME -> SAMPLE_STATE.seriesName
    else -> ""
}

@Composable
private fun BackgroundPicker(state: WidgetEditorState, viewModel: WidgetEditorViewModel) {
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.setBackgroundImage(uri)
    }
    var hex by remember(state.background) { mutableStateOf(state.backgroundValue.takeIf { state.background == WidgetBackground.CUSTOM_COLOR } ?: "") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Background", style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(WidgetBackground.entries) { bg ->
                FilterChip(
                    selected = state.background == bg,
                    onClick = {
                        if (bg == WidgetBackground.CUSTOM_IMAGE) {
                            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        } else {
                            viewModel.setBackground(bg)
                        }
                    },
                    label = { Text(bg.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }) }
                )
            }
        }
        if (state.background == WidgetBackground.CUSTOM_COLOR) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.betteraudio.ui.settings.CUSTOM_COLOR_PRESETS.take(8).forEach { color ->
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(color)
                            .clickable {
                                val h = String.format("#%08X", color.toArgb())
                                hex = h
                                viewModel.setBackgroundColor(h)
                            }
                    )
                }
            }
            OutlinedTextField(
                value = hex,
                onValueChange = { value ->
                    hex = value
                    val withHash = if (value.startsWith("#")) value else "#$value"
                    if (runCatching { android.graphics.Color.parseColor(withHash) }.isSuccess) {
                        viewModel.setBackgroundColor(withHash)
                    }
                },
                label = { Text("Hex (#AARRGGBB)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ElementPropertyPanel(element: WidgetElement, index: Int, viewModel: WidgetEditorViewModel) {
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.setElementImage(index, uri)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            element.type.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.titleSmall
        )
        if (element.type.isText) {
            Text("Font size: ${element.fontSizeSp?.roundToInt() ?: 14} sp", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = element.fontSizeSp ?: 14f,
                onValueChange = { v -> viewModel.updateElement(index) { it.copy(fontSizeSp = v) } },
                valueRange = 8f..48f
            )
        }
        if (element.type == WidgetElementType.SLEEP_TIMER) {
            val minutes = ((element.durationMs ?: 900_000L) / 60_000L).toInt()
            Text("Timer duration: $minutes min", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = minutes.toFloat(),
                onValueChange = { v -> viewModel.updateElement(index) { it.copy(durationMs = v.roundToInt() * 60_000L) } },
                valueRange = 5f..120f,
                steps = 22
            )
        }
        if (element.type == WidgetElementType.CUSTOM_IMAGE) {
            Button(onClick = {
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text(if (element.imagePath.isNullOrBlank()) "Choose image" else "Change image") }
        }
    }
}
