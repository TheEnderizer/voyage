package com.betteraudio.widget.custom

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betteraudio.data.db.dao.CustomWidgetDesignDao
import com.betteraudio.data.db.dao.WidgetBindingDao
import com.betteraudio.data.db.entities.CustomWidgetDesign
import com.betteraudio.data.db.entities.WidgetBinding
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.ui.theme.VoyageTheme
import com.betteraudio.widget.WidgetRender
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CustomWidgetConfigureViewModel @Inject constructor(
    private val designDao: CustomWidgetDesignDao,
    private val bindingDao: WidgetBindingDao,
    val settings: SettingsStore
) : ViewModel() {
    private val _designs = MutableStateFlow<List<CustomWidgetDesign>>(emptyList())
    val designs: StateFlow<List<CustomWidgetDesign>> = _designs.asStateFlow()

    init {
        viewModelScope.launch {
            designDao.observeAll().collect { _designs.value = it }
        }
    }

    fun bind(appWidgetId: Int, designId: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            bindingDao.upsert(WidgetBinding(appWidgetId, designId))
            onDone()
        }
    }
}

/**
 * android:configure activity for the 4 custom-widget providers. Binds the placed appWidgetId to
 * a chosen [CustomWidgetDesign] in [WidgetBindingDao], then triggers a render via the normal
 * ACTION_UPDATE_WIDGET broadcast path before finishing.
 */
@AndroidEntryPoint
class CustomWidgetConfigureActivity : ComponentActivity() {

    private val viewModel: CustomWidgetConfigureViewModel by viewModels()
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            VoyageTheme {
                ConfigureScreen(
                    viewModel = viewModel,
                    onPick = { designId ->
                        viewModel.bind(appWidgetId, designId) { finishWithResult() }
                    },
                    onCreateNew = { openEditor() }
                )
            }
        }
    }

    private fun openEditor() {
        val intent = Intent(this, com.betteraudio.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(WidgetRender.EXTRA_OPEN_WIDGET_EDITOR, true)
        }
        startActivity(intent)
        // The user creates the design in the app, then re-adds the widget to pick it — this
        // configure session ends here (RESULT_CANCELED already set).
        finish()
    }

    private fun finishWithResult() {
        // Trigger a normal broadcast-driven render (all custom-widget ids, incl. this one) from
        // the app's cached last playback state.
        WidgetRender.refresh(this)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(Activity.RESULT_OK, result)
        finish()
    }
}

@Composable
private fun ConfigureScreen(
    viewModel: CustomWidgetConfigureViewModel,
    onPick: (Long) -> Unit,
    onCreateNew: () -> Unit
) {
    val designs by viewModel.designs.collectAsState()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Choose a widget") }) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Button(onClick = onCreateNew, modifier = Modifier.fillMaxWidth()) {
                Text("Create new widget")
            }
            if (designs.isEmpty()) {
                Text(
                    "No custom widgets yet — create one first.",
                    modifier = Modifier.padding(top = 24.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(designs) { design ->
                        DesignRow(design = design, onClick = { onPick(design.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DesignRow(design: CustomWidgetDesign, onClick: () -> Unit) {
    val bucket = runCatching { WidgetSizeBucket.valueOf(design.sizeBucket) }.getOrDefault(WidgetSizeBucket.WIDE)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(design.name.ifBlank { "Untitled widget" }, style = MaterialTheme.typography.titleMedium)
            Text(
                "${bucket.cellsW}×${bucket.cellsH}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
