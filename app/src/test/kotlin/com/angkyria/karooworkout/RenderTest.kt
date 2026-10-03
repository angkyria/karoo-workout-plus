package com.angkyria.karooworkout

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.angkyria.karooworkout.data.CoreHeat
import com.angkyria.karooworkout.data.RiderProfile
import com.angkyria.karooworkout.data.WorkoutEngine
import com.angkyria.karooworkout.data.WorkoutStreams
import com.angkyria.karooworkout.data.WorkoutUiState
import com.angkyria.karooworkout.debug.DemoWorkout
import com.angkyria.karooworkout.overlay.PanelMachine.Size
import com.angkyria.karooworkout.overlay.PanelMachine
import com.angkyria.karooworkout.overlay.WorkoutColors
import com.angkyria.karooworkout.overlay.WorkoutOverlayView
import com.angkyria.karooworkout.settings.PageMode
import com.angkyria.karooworkout.settings.Settings
import com.angkyria.karooworkout.settings.TargetDisplay
import com.angkyria.karooworkout.settings.WorkoutField
import io.hammerhead.karooext.models.DataType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws the real overlay view — every size and the page's optional parts — at Karoo 2
 * size (480 x 800; the full page sits below the 60 px ride header). Each variant must
 * draw; the PNGs land in build/screenshots, and the website and README use them.
 * CI puts the Karoo's font (IBM Plex Sans Condensed) where the view looks for it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RenderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val profile = RiderProfile(ftp = 250)

    private val sensors = mapOf(
        DataType.Type.SMOOTHED_3S_AVERAGE_POWER to mapOf(DataType.Field.SMOOTHED_3S_AVERAGE_POWER to 248.0),
        DataType.Type.HEART_RATE to mapOf(DataType.Field.HEART_RATE to 152.0),
        DataType.Type.CADENCE to mapOf(DataType.Field.CADENCE to 91.0),
        DataType.Type.ELAPSED_TIME to mapOf(DataType.Field.SINGLE to 3_266_000.0),
    )
    private val core = mapOf(
        DataType.Type.CORE_TEMP to mapOf(DataType.Field.CORE_TEMP to 38.2),
        DataType.Type.SKIN_TEMP to mapOf(DataType.Field.SKIN_TEMP to 34.6),
        CoreHeat.HEAT_STRAIN to mapOf(DataType.Field.SINGLE to 2.4),
    )

    /** The demo workout through the real parse + engine path, [seconds] in. */
    private fun ridden(seconds: Int): WorkoutUiState.Shown {
        val demo = DemoWorkout(ftp = 250)
        val engine = WorkoutEngine()
        var state: WorkoutUiState? = null
        repeat(seconds) { t ->
            state = engine.update(WorkoutStreams.snapshot(demo.raw()), t * 1000L, false, true)
            demo.tick(1000)
        }
        return state as WorkoutUiState.Shown
    }

    private fun render(
        name: String,
        state: WorkoutUiState.Shown,
        settings: Settings = Settings(),
        size: Size = Size.FULL,
        sys: Map<String, Map<String, Double>> = sensors + core,
    ): Bitmap {
        val machine = PanelMachine()
        when (size) {
            // the workout page, as Karoo shows it when the ride starts: pinned, no handle
            Size.FULL -> machine.onWorkoutStarted(settings.pageMode, settings.autoOpenDrawer, justStarted = true)
            Size.DRAWER -> machine.onWorkoutStarted(PageMode.DRAWER_ONLY, autoOpenDrawer = true)
            Size.CHIP -> machine.onWorkoutStarted(PageMode.DRAWER_ONLY, autoOpenDrawer = false)
        }
        assertEquals(size, machine.size)
        val view = WorkoutOverlayView(context, machine)
        view.update(state, settings, profile, sys)
        val h = when (size) {
            Size.FULL -> SCREEN_H - RIDE_HEADER
            Size.DRAWER -> SCREEN_H * settings.heightPercent / 100
            Size.CHIP -> 60
        }
        val w = if (size == Size.CHIP) view.chipContentWidth(h).coerceAtLeast(SCREEN_W * 3 / 10) else SCREEN_W
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/screenshots").apply { mkdirs() }.resolve("$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return bitmap
    }

    /** The page painted its panel and something on it. */
    private fun assertDrawn(bitmap: Bitmap) {
        assertEquals(WorkoutColors.PANEL, bitmap.getPixel(1, bitmap.height - 2))
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertNotEquals(1, pixels.distinct().size)
    }

    @Test
    fun workoutPage() = assertDrawn(render("workout-page", ridden(190)))

    @Test
    fun workoutPageWithACadenceTargetPausedAndScaled() =
        assertDrawn(render("workout-page-cadence-paused", ridden(215).copy(paused = true, scalePercent = 105)))

    @Test
    fun numericTarget() =
        assertDrawn(render("workout-page-numeric", ridden(190), Settings(targetDisplay = TargetDisplay.NUMERIC)))

    @Test
    fun twoFieldsRemovedAndNoCoreSensor() = assertDrawn(
        render(
            "workout-page-two-fields",
            ridden(190),
            Settings(pageFields = listOf(WorkoutField.POWER_3S, WorkoutField.NONE, WorkoutField.HEART_RATE, WorkoutField.NONE)),
            sys = sensors,
        ),
    )

    @Test
    fun threeFieldsOneSpansTheRow() = assertDrawn(
        render(
            "workout-page-three-fields",
            ridden(190),
            Settings(
                pageFields = listOf(
                    WorkoutField.POWER_3S,
                    WorkoutField.HEART_RATE,
                    WorkoutField.NONE,
                    WorkoutField.RIDE_TIME,
                ),
            ),
        ),
    )

    @Test
    fun noFieldsAtAll() = assertDrawn(
        render(
            "workout-page-no-fields",
            ridden(190),
            Settings(pageFields = List(4) { WorkoutField.NONE }),
            sys = sensors,
        ),
    )

    @Test
    fun workoutDone() = assertDrawn(
        render("workout-done", ridden(190).copy(complete = true, stepRemainingMs = 0, totalRemainingMs = 0)),
    )

    @Test
    fun drawer() {
        val bitmap = render("drawer", ridden(190), Settings(pageMode = PageMode.DRAWER_ONLY), Size.DRAWER)
        assertNotEquals(0, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
    }

    @Test
    fun chip() {
        val bitmap = render("chip", ridden(190), Settings(pageMode = PageMode.DRAWER_ONLY), Size.CHIP)
        assertNotEquals(0, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
    }

    private companion object {
        const val SCREEN_W = 480
        const val SCREEN_H = 800
        const val RIDE_HEADER = 60
    }
}
