package com.angkyria.karooworkout.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.angkyria.karooworkout.debug.Diagnostics
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {

    private lateinit var repo: SettingsRepo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = SettingsRepo(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFC77DFF))) {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    SettingsScreen(
                        repo = repo,
                        modifier = Modifier.padding(padding),
                        canDrawOverlays = { AndroidSettings.canDrawOverlays(this) },
                        requestOverlayPermission = {
                            startActivity(
                                Intent(
                                    AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName"),
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    repo: SettingsRepo,
    modifier: Modifier = Modifier,
    canDrawOverlays: () -> Boolean,
    requestOverlayPermission: () -> Unit,
) {
    val settings by repo.settings.collectAsState(initial = Settings())
    val scope = rememberCoroutineScope()
    fun update(transform: (Settings) -> Settings) {
        scope.launch { repo.update(transform) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Workout+", style = MaterialTheme.typography.titleLarge)

        // ---- Overlay ----
        SectionTitle("Overlay")
        SwitchRow("Enabled", settings.overlayEnabled) { on -> update { it.copy(overlayEnabled = on) } }
        PermissionRow(canDrawOverlays, requestOverlayPermission)

        // ---- Workout page ----
        SectionTitle("Workout page")
        PageMode.entries.forEach { mode ->
            RadioRow(mode.label, settings.pageMode == mode) { update { it.copy(pageMode = mode) } }
        }
        Hint(
            when (settings.pageMode) {
                PageMode.EVERY_PAGE ->
                    "The new layout covers every ride page while a workout runs. The back " +
                        "button shows the page underneath until you change pages."
                PageMode.REPLACE_WORKOUT_PAGE ->
                    "Only the workout page — or a page with the \"Workout+ page\" field — " +
                        "is covered; the other pages show a chip."
                PageMode.DRAWER_ONLY -> "A chip on every page; tap it for the drawer, swipe up for the full page."
            },
        )
        if (settings.pageMode == PageMode.DRAWER_ONLY) {
            SwitchRow("Open the drawer when a workout starts", settings.autoOpenDrawer) { on ->
                update { it.copy(autoOpenDrawer = on) }
            }
        } else {
            SwitchRow("Chip on the other pages", settings.chipOnOtherPages) { on ->
                update { it.copy(chipOnOtherPages = on) }
            }
        }

        // ---- Target ----
        SectionTitle("Workout target field")
        RadioRow("Visual", settings.targetDisplay == TargetDisplay.VISUAL) {
            update { it.copy(targetDisplay = TargetDisplay.VISUAL) }
        }
        RadioRow("Numeric", settings.targetDisplay == TargetDisplay.NUMERIC) {
            update { it.copy(targetDisplay = TargetDisplay.NUMERIC) }
        }
        SwitchRow("Show secondary target", settings.showSecondary) { on -> update { it.copy(showSecondary = on) } }
        SwitchRow("Smoothed output", settings.smoothedOutput) { on -> update { it.copy(smoothedOutput = on) } }

        // ---- Position & size ----
        SectionTitle("Chip position")
        RadioRow("Bottom", settings.chipAnchor == OverlayAnchor.BOTTOM) {
            update { it.copy(chipAnchor = OverlayAnchor.BOTTOM) }
        }
        RadioRow("Top", settings.chipAnchor == OverlayAnchor.TOP) {
            update { it.copy(chipAnchor = OverlayAnchor.TOP) }
        }
        SectionTitle("Drawer position")
        RadioRow("Bottom", settings.drawerAnchor == OverlayAnchor.BOTTOM) {
            update { it.copy(drawerAnchor = OverlayAnchor.BOTTOM) }
        }
        RadioRow("Top", settings.drawerAnchor == OverlayAnchor.TOP) {
            update { it.copy(drawerAnchor = OverlayAnchor.TOP) }
        }
        SectionTitle("Drawer height: ${settings.heightPercent}%")
        Slider(
            value = settings.heightPercent.toFloat(),
            onValueChange = { v -> update { it.copy(heightPercent = v.toInt()) } },
            valueRange = 30f..70f,
        )
        SectionTitle("Chip / drawer opacity: ${settings.opacityPercent}%")
        Slider(
            value = settings.opacityPercent.toFloat(),
            onValueChange = { v -> update { it.copy(opacityPercent = v.toInt()) } },
            valueRange = 75f..100f,
        )

        // ---- Page fields ----
        SectionTitle("Workout page fields")
        settings.pageFields.forEachIndexed { index, field ->
            FieldPickerRow("Field ${index + 1}", field) { chosen ->
                update { s -> s.copy(pageFields = s.pageFields.toMutableList().also { it[index] = chosen }) }
            }
        }

        // ---- Debug ----
        SectionTitle("Debug")
        SwitchRow("Demo mode (synthetic workout, no ride needed)", settings.demoMode) { on ->
            update { it.copy(demoMode = on) }
        }
        DiagnosticsPanel()

        HorizontalDivider()
        Text(
            "Layout after the Karoo OS workout drawer. Overlay window pattern from " +
                "Climber+ (hazzus) and Ki2 (valterc). Built with Hammerhead karoo-ext.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** What Karoo really streams — workout field codes and units are undocumented in karoo-ext. */
@Composable
private fun DiagnosticsPanel() {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(if (open) "Hide diagnostics" else "Show diagnostics") }
    if (!open) return
    val streams by Diagnostics.workoutStreams.collectAsState()
    val page by Diagnostics.page.collectAsState()
    val hardware by Diagnostics.hardware.collectAsState()
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    Text("Device: ${hardware ?: "not connected"}", style = mono)
    Text(
        page?.let { (ids, workout) ->
            "Ride page (workout page: $workout):\n" + ids.joinToString("\n") { "  ${it.short()}" }
        } ?: "Ride page: none reported yet",
        style = mono,
    )
    if (streams.isEmpty()) {
        Text("No workout streams (start a ride with a workout, or demo mode)", style = mono)
    }
    streams.toSortedMap().forEach { (type, fields) ->
        Text(
            type.short() + "\n" + fields.toSortedMap().entries.joinToString("\n") { (f, v) ->
                "  ${f.short()} = ${if (v == v.toLong().toDouble()) v.toLong() else v}"
            },
            style = mono,
        )
    }
}

private fun String.short(): String = removePrefix("TYPE_").removePrefix("FIELD_").removeSuffix("_ID")

@Composable
private fun FieldPickerRow(label: String, selected: WorkoutField, onSelect: (WorkoutField) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        TextButton(onClick = { open = true }) {
            Column(horizontalAlignment = Alignment.End) {
                Text(selected.label)
                Text(
                    selected.category.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (open) {
        Dialog(onDismissRequest = { open = false }) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    WorkoutField.entries.groupBy { it.category }.forEach { (category, fields) ->
                        item {
                            Text(
                                category.label,
                                Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        items(fields) { field ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        open = false
                                        onSelect(field)
                                    },
                            ) {
                                RadioButton(
                                    selected = field == selected,
                                    onClick = {
                                        open = false
                                        onSelect(field)
                                    },
                                )
                                Text(field.label)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun PermissionRow(canDrawOverlays: () -> Boolean, request: () -> Unit) {
    var granted by remember { mutableStateOf(canDrawOverlays()) }
    LaunchedEffect(Unit) {
        // refresh when returning from the system permission screen
        while (true) {
            granted = canDrawOverlays()
            kotlinx.coroutines.delay(1000)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            if (granted) "Draw-over-apps permission: granted" else "Draw-over-apps permission: MISSING",
            Modifier.weight(1f),
            color = if (granted) Color.Unspecified else MaterialTheme.colorScheme.error,
        )
        if (!granted) {
            Button(onClick = request, shape = RoundedCornerShape(8.dp)) { Text("Grant") }
        }
    }
}
