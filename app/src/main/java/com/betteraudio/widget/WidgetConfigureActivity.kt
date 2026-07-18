package com.betteraudio.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betteraudio.data.db.dao.WidgetBindingDao
import com.betteraudio.data.db.dao.WidgetDesignDao
import com.betteraudio.data.db.entities.WidgetBinding
import com.betteraudio.data.db.entities.WidgetDesign
import com.betteraudio.ui.theme.VoyageTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WidgetConfigureViewModel @Inject constructor(
    private val designDao: WidgetDesignDao,
    private val bindingDao: WidgetBindingDao,
    private val updater: WidgetUpdater,
) : ViewModel() {
    private val _designs = MutableStateFlow<List<WidgetDesign>>(emptyList())
    val designs: StateFlow<List<WidgetDesign>> = _designs.asStateFlow()

    init {
        viewModelScope.launch { designDao.observeAll().collect { _designs.value = it } }
    }

    fun bind(appWidgetId: Int, designId: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            bindingDao.upsert(WidgetBinding(appWidgetId, designId, System.currentTimeMillis()))
            updater.renderOneAsync(appWidgetId)
            onDone()
        }
    }
}

/**
 * android:configure activity — also reachable by tapping the "pick a design" placeholder on an
 * unbound/deleted-design widget (see WidgetUpdater.renderPlaceholder), and via long-press
 * "reconfigure" since the provider declares widgetFeatures="reconfigurable". Binds the placed
 * appWidgetId to a chosen design, triggers a direct render (no broadcast round-trip), then
 * finishes.
 */
@AndroidEntryPoint
class WidgetConfigureActivity : ComponentActivity() {

    private val viewModel: WidgetConfigureViewModel by viewModels()
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
                    onPick = { designId -> viewModel.bind(appWidgetId, designId) { finishWithResult() } },
                    onCreateNew = { openGallery() }
                )
            }
        }
    }

    private fun openGallery() {
        val intent = Intent(this, com.betteraudio.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(WidgetIntents.EXTRA_OPEN_WIDGET_GALLERY, true)
        }
        startActivity(intent)
        // The user designs/picks in the app, then re-adds the widget — this configure session
        // ends here (RESULT_CANCELED already set).
        finish()
    }

    private fun finishWithResult() {
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(Activity.RESULT_OK, result)
        finish()
    }
}

@Composable
private fun ConfigureScreen(
    viewModel: WidgetConfigureViewModel,
    onPick: (Long) -> Unit,
    onCreateNew: () -> Unit,
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
                    "No widget designs yet — create one first.",
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
private fun DesignRow(design: WidgetDesign, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(design.name.ifBlank { "Untitled widget" }, style = MaterialTheme.typography.titleMedium)
            Text(
                "Aspect ${"%.2f".format(design.aspectRatio)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
