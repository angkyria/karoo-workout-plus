package com.angkyria.karooworkout.settings

import com.angkyria.karooworkout.data.CoreHeat
import io.hammerhead.karooext.models.DataType

/** How the overlay relates to the ride app's pages. Listed in the settings in this order. */
enum class PageMode(val label: String) {
    /** Full screen on the workout page (it replaces it); the other pages stay usable, with a chip. */
    REPLACE_WORKOUT_PAGE("Replace the workout page"),

    /** Full screen on every ride page while a workout runs; minimizing shows the page until the next page change. */
    EVERY_PAGE("Cover every page"),

    /** Never full screen by itself: a chip that opens into a drawer (Climber+ style). */
    DRAWER_ONLY("Chip + drawer only"),
}

/** Native "workout target field" options: a visual bar or a plain number. */
enum class TargetDisplay { VISUAL, NUMERIC }

/** Where the chip / drawer window is anchored. The full page is always bottom. */
enum class OverlayAnchor { TOP, BOTTOM }

/** Grouping for the field-picker menu. */
enum class FieldCategory(val label: String) {
    WORKOUT("Workout"),
    POWER("Power"),
    HEART_RATE("Heart rate"),
    CADENCE("Cadence"),
    SPEED("Speed & distance"),
    TIME("Time"),
    LAP("Lap"),
    CORE_HEAT("Core heat (CORE sensor)"),
}

/** How a system stream's value is rendered in a page-field cell. */
enum class FieldFormat {
    /** Computed by the workout engine, not a system stream. */
    DERIVED,

    /** Rounded integer (W, bpm, rpm, zones, kJ). */
    INTEGER,

    /** One decimal. */
    NUMBER,

    /** Two decimals (IF, W/kg). */
    NUMBER2,

    /** m/s -> km/h or mph. */
    SPEED,

    /** Meters -> km or mi. */
    DISTANCE,

    /** Elapsed time -> m:ss / h:mm:ss. */
    DURATION,

    /** °C, one decimal; °F for imperial. */
    TEMPERATURE,
}

/**
 * Data fields for the 2x2 grid at the bottom of the workout page. Derived fields
 * have no [dataTypeId]; system fields stream from the Karoo data type they name.
 * Entry names are persisted in DataStore — never rename existing ones.
 */
enum class WorkoutField(
    val label: String,
    val category: FieldCategory,
    val format: FieldFormat,
    val dataTypeId: String? = null,
    /** Field to read when the stream carries more than the value (e.g. a quality flag). */
    val valueField: String? = null,
    /** A second stream the cell needs, e.g. the heat index that colors a temperature. */
    val extraDataTypeId: String? = null,
) {
    // workout engine
    WORKOUT_IN_RANGE("IN RANGE %", FieldCategory.WORKOUT, FieldFormat.DERIVED),
    INTERVAL_IN_RANGE("INTERVAL IN RANGE %", FieldCategory.WORKOUT, FieldFormat.DERIVED),
    WORKOUT_ELAPSED("WORKOUT TIME", FieldCategory.WORKOUT, FieldFormat.DERIVED),
    WORKOUT_REMAINING("WORKOUT LEFT", FieldCategory.WORKOUT, FieldFormat.DERIVED),
    INTERVAL_COUNT("INTERVAL", FieldCategory.WORKOUT, FieldFormat.DERIVED),
    SCALE("SCALE %", FieldCategory.WORKOUT, FieldFormat.DERIVED),

    // power
    POWER("POWER", FieldCategory.POWER, FieldFormat.INTEGER, DataType.Type.POWER),
    POWER_3S("3S POWER", FieldCategory.POWER, FieldFormat.INTEGER, DataType.Type.SMOOTHED_3S_AVERAGE_POWER),
    POWER_10S("10S POWER", FieldCategory.POWER, FieldFormat.INTEGER, DataType.Type.SMOOTHED_10S_AVERAGE_POWER),
    POWER_30S("30S POWER", FieldCategory.POWER, FieldFormat.INTEGER, DataType.Type.SMOOTHED_30S_AVERAGE_POWER),
    AVG_POWER("AVG POWER", FieldCategory.POWER, FieldFormat.INTEGER, DataType.Type.AVERAGE_POWER),
    NORMALIZED_POWER("NP", FieldCategory.POWER, FieldFormat.INTEGER, DataType.Type.NORMALIZED_POWER),
    POWER_ZONE("POWER ZONE", FieldCategory.POWER, FieldFormat.INTEGER, DataType.Type.POWER_ZONE),
    POWER_TO_WEIGHT("W/KG", FieldCategory.POWER, FieldFormat.NUMBER2, DataType.Type.POWER_TO_WEIGHT),
    INTENSITY_FACTOR("IF", FieldCategory.POWER, FieldFormat.NUMBER2, DataType.Type.INTENSITY_FACTOR),
    TSS("TSS", FieldCategory.POWER, FieldFormat.INTEGER, DataType.Type.TRAINING_STRESS_SCORE),
    ENERGY("KJ", FieldCategory.POWER, FieldFormat.INTEGER, DataType.Type.ENERGY_OUTPUT),

    // heart rate
    HEART_RATE("HEART RATE", FieldCategory.HEART_RATE, FieldFormat.INTEGER, DataType.Type.HEART_RATE),
    HR_ZONE("HR ZONE", FieldCategory.HEART_RATE, FieldFormat.INTEGER, DataType.Type.HR_ZONE),
    AVG_HR_LAP("LAP HR", FieldCategory.HEART_RATE, FieldFormat.INTEGER, DataType.Type.AVERAGE_LAP_HR),

    // cadence
    CADENCE("CADENCE", FieldCategory.CADENCE, FieldFormat.INTEGER, DataType.Type.CADENCE),
    CADENCE_3S("3S CADENCE", FieldCategory.CADENCE, FieldFormat.INTEGER, DataType.Type.SMOOTHED_3S_AVERAGE_CADENCE),
    AVG_CADENCE("AVG CADENCE", FieldCategory.CADENCE, FieldFormat.INTEGER, DataType.Type.AVERAGE_CADENCE),

    // speed & distance
    SPEED("SPEED", FieldCategory.SPEED, FieldFormat.SPEED, DataType.Type.SPEED),
    AVG_SPEED("AVG SPEED", FieldCategory.SPEED, FieldFormat.SPEED, DataType.Type.AVERAGE_SPEED),
    DISTANCE("DISTANCE", FieldCategory.SPEED, FieldFormat.DISTANCE, DataType.Type.DISTANCE),

    // time
    RIDE_TIME("RIDE TIME", FieldCategory.TIME, FieldFormat.DURATION, DataType.Type.ELAPSED_TIME),
    TOTAL_TIME("TOTAL TIME", FieldCategory.TIME, FieldFormat.DURATION, DataType.Type.RIDE_TIME),

    // lap (Karoo starts a lap at every workout interval)
    LAP_POWER("LAP POWER", FieldCategory.LAP, FieldFormat.INTEGER, DataType.Type.POWER_LAP),
    LAP_NP("LAP NP", FieldCategory.LAP, FieldFormat.INTEGER, DataType.Type.NORMALIZED_POWER_LAP),
    LAP_CADENCE("LAP CADENCE", FieldCategory.LAP, FieldFormat.INTEGER, DataType.Type.CADENCE_LAP),
    LAP_TIME("LAP TIME", FieldCategory.LAP, FieldFormat.DURATION, DataType.Type.ELAPSED_TIME_LAP),

    // core heat: the Karoo's CORE streams, colored by the heat zone, and CORE Heat's metrics
    CORE_TEMP(
        "CORE TEMP", FieldCategory.CORE_HEAT, FieldFormat.TEMPERATURE, DataType.Type.CORE_TEMP,
        valueField = DataType.Field.CORE_TEMP, extraDataTypeId = CoreHeat.HEAT_STRAIN,
    ),
    SKIN_TEMP(
        "SKIN TEMP", FieldCategory.CORE_HEAT, FieldFormat.TEMPERATURE, DataType.Type.SKIN_TEMP,
        valueField = DataType.Field.SKIN_TEMP, extraDataTypeId = CoreHeat.HEAT_STRAIN,
    ),
    MAX_CORE_TEMP(
        "MAX CORE", FieldCategory.CORE_HEAT, FieldFormat.TEMPERATURE, DataType.Type.MAX_CORE_TEMP,
        valueField = DataType.Field.CORE_TEMP,
    ),
    HEAT_STRAIN("HEAT STRAIN", FieldCategory.CORE_HEAT, FieldFormat.NUMBER, CoreHeat.HEAT_STRAIN),
    HEAT_ZONE("HEAT ZONE", FieldCategory.CORE_HEAT, FieldFormat.INTEGER, CoreHeat.HEAT_ZONE),
    HEAT_LOAD("HEAT LOAD", FieldCategory.CORE_HEAT, FieldFormat.NUMBER, CoreHeat.HEAT_LOAD),
    HEAT_ADAPTATION("HEAT ADAPT %", FieldCategory.CORE_HEAT, FieldFormat.INTEGER, CoreHeat.HEAT_ADAPTATION),
}

data class Settings(
    val overlayEnabled: Boolean = true,
    val pageMode: PageMode = PageMode.REPLACE_WORKOUT_PAGE,
    /** Show the small chip on pages that aren't taken over (tap it to open the drawer). */
    val chipOnOtherPages: Boolean = true,
    /** DRAWER_ONLY mode: pop the drawer open when a workout starts. */
    val autoOpenDrawer: Boolean = true,
    val targetDisplay: TargetDisplay = TargetDisplay.VISUAL,
    val showSecondary: Boolean = true,
    /** Judge and show the smoothed output Karoo streams with the target (vs. instant). */
    val smoothedOutput: Boolean = true,
    /** Core / heat index / skin strip on the workout page whenever a CORE sensor streams. */
    val coreHeatStrip: Boolean = true,
    val chipAnchor: OverlayAnchor = OverlayAnchor.BOTTOM,
    val drawerAnchor: OverlayAnchor = OverlayAnchor.BOTTOM,
    /** Drawer height as % of the screen. */
    val heightPercent: Int = 48,
    /** Overlay opacity 75..100. */
    val opacityPercent: Int = 100,
    /** Feed a synthetic workout (on-desk testing, no ride or trainer needed). */
    val demoMode: Boolean = false,
    /** The four data fields at the bottom of the workout page. */
    val pageFields: List<WorkoutField> = listOf(
        WorkoutField.POWER_3S,
        WorkoutField.HEART_RATE,
        WorkoutField.CADENCE,
        WorkoutField.WORKOUT_IN_RANGE,
    ),
)
