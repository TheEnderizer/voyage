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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
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
                    OutlinedButton(onClick = viewModel::deleteSelected) {
                        Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Delete")
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

@Composable
private fun WidgetCanvas(state: WidgetEditorState, viewModel: WidgetEditorViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val bucket = state.sizeBucket
    val aspect = bucket.cellsW.toFloat() / bucket.cellsH.toFloat()

    var canvasWidthPx by remember { mutableStateOf(0) }
    var canvasHeightPx by remember { mutableStateOf(0) }

    val design = remember(state.sizeBucket, state.background, state.backgroundValue, state.elements) {
        com.betteraudio.data.db.entities.CustomWidgetDesign(
            id = state.designId,
            name = state.name,
            sizeBucket = state.sizeBucket.name,
            backgroundType = state.background.name,
            backgroundValue = state.backgroundValue,
            elementsJson = com.betteraudio.widget.custom.WidgetElementCodec.encode(state.elements)
        )
    }

    val primaryArgb = MaterialTheme.colorScheme.primary.toArgb()
    val bitmap = remember(design, canvasWidthPx, canvasHeightPx, primaryArgb) {
        if (canvasWidthPx <= 0 || canvasHeightPx <= 0) null
        else CustomWidgetRenderer.render(
            context, design, SAMPLE_STATE, appColor = primaryArgb,
            hideWhenIdle = false, pxW = canvasWidthPx, pxH = canvasHeightPx
        )
    }

    Box(
        modifier
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .onSizeChanged(density) { w, h -> canvasWidthPx = w; canvasHeightPx = h }
    ) {
        bitmap?.let {
            androidx.compose.foundation.Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        }

        state.elements.forEachIndexed { index, el ->
            ElementOverlay(
                element = el,
                selected = index == state.selectedIndex,
                onSelect = { viewModel.select(index) },
                onDrag = { dxFrac, dyFrac ->
                    viewModel.updateElement(index) { current ->
                        current.copy(
                            x = (current.x + dxFrac).coerceIn(0f, 1f - current.w),
                            y = (current.y + dyFrac).coerceIn(0f, 1f - current.h)
                        )
                    }
                }
            )
        }
    }
}

/** Reports the composable's measured pixel size once known (used to size the render bitmap). */
private fun Modifier.onSizeChanged(density: androidx.compose.ui.unit.Density, onSize: (Int, Int) -> Unit): Modifier =
    this.then(Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        onSize(placeable.width, placeable.height)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    })

@Composable
private fun BoxScope.ElementOverlay(
    element: WidgetElement,
    selected: Boolean,
    onSelect: () -> Unit,
    onDrag: (dxFrac: Float, dyFrac: Float) -> Unit
) {
    var boxSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                boxSize = androidx.compose.ui.unit.IntSize(placeable.width, placeable.height)
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
    ) {
        val w = boxSize.width
        val h = boxSize.height
        if (w > 0 && h > 0) {
            val leftPx = (element.x * w).roundToInt()
            val topPx = (element.y * h).roundToInt()
            val wPx = (element.w * w).roundToInt().coerceAtLeast(8)
            val hPx = (element.h * h).roundToInt().coerceAtLeast(8)

            Box(
                Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(leftPx, topPx) }
                    .size(
                        with(LocalDensity.current) { wPx.toDp() },
                        with(LocalDensity.current) { hPx.toDp() }
                    )
                    .border(
                        2.dp,
                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelect() }
                    .pointerInput(element) {
                        detectDragGestures(onDragStart = { onSelect() }) { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x / w, dragAmount.y / h)
                        }
                    }
            )
        }
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
private fun ElementTypeIcon(type: WidgetElementType, contentDescription: String?, modifier: Modifier = Modifier) {
    when (type) {
        WidgetElementType.PLAY_PAUSE -> Icon(painterResource(R.drawable.ic_play), contentDescription, modifier)
        WidgetElementType.SKIP_FORWARD -> Icon(painterResource(R.drawable.ic_skip_forward), contentDescription, modifier)
        WidgetElementType.SKIP_BACK -> Icon(painterResource(R.drawable.ic_skip_back), contentDescription, modifier)
        WidgetElementType.CHAPTER_FORWARD -> Icon(painterResource(R.drawable.ic_chapter_forward), contentDescription, modifier)
        WidgetElementType.CHAPTER_BACK -> Icon(painterResource(R.drawable.ic_chapter_back), contentDescription, modifier)
        WidgetElementType.SPEED_UP -> Icon(painterResource(R.drawable.ic_speed_up), contentDescription, modifier)
        WidgetElementType.SPEED_DOWN -> Icon(painterResource(R.drawable.ic_speed_down), contentDescription, modifier)
        WidgetElementType.BOOST_UP -> Icon(painterResource(R.drawable.ic_boost_up), contentDescription, modifier)
        WidgetElementType.BOOST_DOWN -> Icon(painterResource(R.drawable.ic_boost_down), contentDescription, modifier)
        WidgetElementType.SLEEP_TIMER -> Icon(painterResource(R.drawable.ic_sleep), contentDescription, modifier)
        WidgetElementType.QUICK_BOOKMARK -> Icon(painterResource(R.drawable.ic_bookmark_add), contentDescription, modifier)
        WidgetElementType.CLOSE_BOOK -> Icon(painterResource(R.drawable.ic_close_book), contentDescription, modifier)
        WidgetElementType.BOOK_NAME -> Icon(Icons.Default.Title, contentDescription, modifier)
        WidgetElementType.AUTHOR_NAME -> Icon(Icons.Default.Person, contentDescription, modifier)
        WidgetElementType.CHAPTER_NAME -> Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription, modifier)
        WidgetElementType.SERIES_NAME -> Icon(Icons.Default.CollectionsBookmark, contentDescription, modifier)
        WidgetElementType.BOOK_COVER -> Icon(Icons.Default.Image, contentDescription, modifier)
        WidgetElementType.SERIES_COVER -> Icon(Icons.Default.PhotoLibrary, contentDescription, modifier)
        WidgetElementType.CUSTOM_IMAGE -> Icon(Icons.Default.AddPhotoAlternate, contentDescription, modifier)
    }
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
