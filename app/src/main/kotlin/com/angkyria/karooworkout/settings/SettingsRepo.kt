package com.angkyria.karooworkout.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepo(private val context: Context) {

    private object Keys {
        val version = intPreferencesKey("settings_version")
        val overlayEnabled = booleanPreferencesKey("overlay_enabled")
        val pageMode = stringPreferencesKey("page_mode")
        val chipOnOtherPages = booleanPreferencesKey("chip_on_other_pages")
        val autoOpenDrawer = booleanPreferencesKey("auto_open_drawer")
        val targetDisplay = stringPreferencesKey("target_display")
        val showSecondary = booleanPreferencesKey("show_secondary")
        val smoothedOutput = booleanPreferencesKey("smoothed_output")
        val coreHeatStrip = booleanPreferencesKey("core_heat_strip")
        val chipAnchor = stringPreferencesKey("chip_anchor")
        val drawerAnchor = stringPreferencesKey("drawer_anchor")
        val heightPercent = intPreferencesKey("height_percent")
        val opacityPercent = intPreferencesKey("opacity_percent")
        val demoMode = booleanPreferencesKey("demo_mode")
        val pageFields = stringPreferencesKey("page_fields")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { it.toSettings() }.distinctUntilChanged()

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { p -> p.write(transform(p.toSettings())) }
    }

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        // every edit saves the whole settings, page mode included: when a version
        // changes the default mode, the mode stored by older versions is dropped once
        // (v2 tried "cover every page"; v3 is back to replacing the workout page)
        val current = (this[Keys.version] ?: 1) >= SETTINGS_VERSION
        return Settings(
            overlayEnabled = this[Keys.overlayEnabled] ?: d.overlayEnabled,
            pageMode = if (current) enumOr(this[Keys.pageMode], d.pageMode) else d.pageMode,
            chipOnOtherPages = this[Keys.chipOnOtherPages] ?: d.chipOnOtherPages,
            autoOpenDrawer = this[Keys.autoOpenDrawer] ?: d.autoOpenDrawer,
            targetDisplay = enumOr(this[Keys.targetDisplay], d.targetDisplay),
            showSecondary = this[Keys.showSecondary] ?: d.showSecondary,
            smoothedOutput = this[Keys.smoothedOutput] ?: d.smoothedOutput,
            coreHeatStrip = this[Keys.coreHeatStrip] ?: d.coreHeatStrip,
            chipAnchor = enumOr(this[Keys.chipAnchor], d.chipAnchor),
            drawerAnchor = enumOr(this[Keys.drawerAnchor], d.drawerAnchor),
            heightPercent = (this[Keys.heightPercent] ?: d.heightPercent).coerceIn(30, 70),
            opacityPercent = (this[Keys.opacityPercent] ?: d.opacityPercent).coerceIn(75, 100),
            demoMode = this[Keys.demoMode] ?: d.demoMode,
            pageFields = this[Keys.pageFields]?.parseFields() ?: d.pageFields,
        )
    }

    private fun MutablePreferences.write(s: Settings) {
        this[Keys.version] = SETTINGS_VERSION
        this[Keys.overlayEnabled] = s.overlayEnabled
        this[Keys.pageMode] = s.pageMode.name
        this[Keys.chipOnOtherPages] = s.chipOnOtherPages
        this[Keys.autoOpenDrawer] = s.autoOpenDrawer
        this[Keys.targetDisplay] = s.targetDisplay.name
        this[Keys.showSecondary] = s.showSecondary
        this[Keys.smoothedOutput] = s.smoothedOutput
        this[Keys.coreHeatStrip] = s.coreHeatStrip
        this[Keys.chipAnchor] = s.chipAnchor.name
        this[Keys.drawerAnchor] = s.drawerAnchor.name
        this[Keys.heightPercent] = s.heightPercent
        this[Keys.opacityPercent] = s.opacityPercent
        this[Keys.demoMode] = s.demoMode
        this[Keys.pageFields] = s.pageFields.joinToString(",") { it.name }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    private fun String.parseFields(): List<WorkoutField>? =
        split(',')
            .mapNotNull { name -> runCatching { WorkoutField.valueOf(name.trim()) }.getOrNull() }
            .takeIf { it.size == 4 }

    private companion object {
        const val SETTINGS_VERSION = 3
    }
}
