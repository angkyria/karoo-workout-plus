package com.angkyria.karooworkout

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.test.core.app.ApplicationProvider
import com.angkyria.karooworkout.data.CoreHeat
import com.angkyria.karooworkout.data.CueFrame
import com.angkyria.karooworkout.data.Flash
import com.angkyria.karooworkout.data.NextList
import com.angkyria.karooworkout.data.PlanTracker
import com.angkyria.karooworkout.data.RiderProfile
import com.angkyria.karooworkout.data.TargetKind
import com.angkyria.karooworkout.data.WorkoutEngine
import com.angkyria.karooworkout.data.WorkoutStreams
import com.angkyria.karooworkout.data.WorkoutUiState
import com.angkyria.karooworkout.debug.DemoWorkout
import com.angkyria.karooworkout.overlay.IntervalListDrawer
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    /** [seconds] into the demo with the NEXT list its own plan gives (watched from the start). */
    private fun planned(seconds: Int): WorkoutUiState.Shown {
        val demo = DemoWorkout(ftp = 250)
        val tracker = PlanTracker().apply { onLibrary(listOf(demo.libraryWorkout())) }
        val engine = WorkoutEngine()
        var state: WorkoutUiState.Shown? = null
        var next: NextList? = null
        repeat(seconds) { t ->
            state = engine.update(WorkoutStreams.snapshot(demo.raw()), t * 1000L, false, true) as WorkoutUiState.Shown
            next = tracker.update(state!!, watchedStart = true, todayStartMs = 0)
            demo.tick(1000)
        }
        assertTrue("the demo plan matches: $next", next is NextList.Planned)
        return state!!.copy(next = next)
    }

    private fun render(
        name: String,
        state: WorkoutUiState.Shown,
        settings: Settings = Settings(),
        size: Size = Size.FULL,
        sys: Map<String, Map<String, Double>> = sensors + core,
        cues: CueFrame = CueFrame(),
        scrollPx: Float = 0f,
    ): Bitmap = draw(name, pageView(state, settings, size, sys, cues).also { it.listScroll.offsetPx = scrollPx })

    private fun pageView(
        state: WorkoutUiState.Shown,
        settings: Settings = Settings(),
        size: Size = Size.FULL,
        sys: Map<String, Map<String, Double>> = sensors + core,
        cues: CueFrame = CueFrame(),
        machine: PanelMachine = PanelMachine().apply {
            when (size) {
                // the workout page, as Karoo shows it when the ride starts: pinned, no handle
                Size.FULL -> onWorkoutStarted(settings.pageMode, settings.autoOpenDrawer, justStarted = true)
                Size.DRAWER -> onWorkoutStarted(PageMode.DRAWER_ONLY, autoOpenDrawer = true)
                Size.CHIP -> onWorkoutStarted(PageMode.DRAWER_ONLY, autoOpenDrawer = false)
            }
        },
    ): WorkoutOverlayView {
        assertEquals(size, machine.size)
        val view = WorkoutOverlayView(context, machine)
        view.update(state, settings, profile, sys, cues)
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
        return view
    }

    private fun draw(name: String, view: WorkoutOverlayView): Bitmap {
        val w = view.width
        val h = view.height
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
    fun tenSecondPowerWithACadenceTarget() {
        // the cadence step of the demo (215 s in), power on the rider's 10 s average
        val s = ridden(215)
        val tenS = s.copy(primary = s.primary!!.copy(averageSeconds = 10))
        assertDrawn(render("workout-page-power-10s", tenS))
    }

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
    fun intervalRecap() {
        val s = ridden(190)
        assertDrawn(render("workout-page-recap", s, cues = CueFrame(recap = s.history[s.history.size - 2])))
    }

    @Test
    fun countdownPulse() {
        val s = ridden(190).copy(stepRemainingMs = 4_000)
        assertDrawn(render("workout-page-pulse", s, cues = CueFrame(pulse = true)))
        render("drawer-pulse", s, Settings(pageMode = PageMode.DRAWER_ONLY), Size.DRAWER, cues = CueFrame(pulse = true))
    }

    @Test
    fun screenFlash() {
        val s = ridden(190).copy(stepRemainingMs = 4_000)
        val flash = CueFrame(pulse = true, flash = Flash.COUNTDOWN)
        val page = render("workout-page-flash", s, cues = flash)
        assertEquals(WorkoutColors.ENDING, page.getPixel(3, page.height / 2))
        render("drawer-flash", s, Settings(pageMode = PageMode.DRAWER_ONLY), Size.DRAWER, cues = flash)
        val chip = render("chip-flash", s, Settings(pageMode = PageMode.DRAWER_ONLY), Size.CHIP, cues = flash)
        assertEquals(WorkoutColors.ENDING, chip.getPixel(chip.width / 2, 2))
        val go = render("workout-page-flash-go", ridden(190), cues = CueFrame(flash = Flash.GO))
        assertEquals(WorkoutColors.IN_RANGE, go.getPixel(3, go.height / 2))
        // the setting off: no frame
        val off = render("workout-page-flash-off", s, Settings(flashScreen = false), cues = flash)
        assertEquals(WorkoutColors.PANEL, off.getPixel(3, off.height / 2))
    }

    @Test
    fun workoutSummaryEveryIntervalFits() = assertDrawn(
        render("workout-summary", ridden(400).copy(complete = true, stepRemainingMs = 0, totalRemainingMs = 0)),
    )

    @Test
    fun workoutSummaryTooManyIntervals() {
        val s = ridden(190)
        val many = (0 until 14).map { i -> s.history[i % s.history.size].copy(index = i) }
        assertDrawn(
            render(
                "workout-summary-long",
                s.copy(history = many, stepCount = 14, stepIndex = 13, complete = true, stepRemainingMs = 0, totalRemainingMs = 0),
            ),
        )
    }

    // ------------------------------------------------------- workout complete

    private fun finished(): WorkoutUiState.Shown =
        ridden(520).copy(complete = true, stepRemainingMs = 0, totalRemainingMs = 0)

    /** Some pixel in rows [fromFrac]..[toFrac] of the height has exactly [color]. */
    private fun Bitmap.hasColor(color: Int, fromFrac: Float, toFrac: Float): Boolean {
        for (y in (height * fromFrac).toInt() until (height * toFrac).toInt()) {
            for (x in 0 until width) if (getPixel(x, y) == color) return true
        }
        return false
    }

    @Test
    fun workoutComplete() {
        val s = finished()
        assertTrue(s.history.all { it.trace.isNotEmpty() })
        val page = render("workout-complete", s)
        assertDrawn(page)
        // the graph: target blocks and the ridden line
        assertTrue(page.hasColor(WorkoutColors.BAND, 0.15f, 0.4f))
        assertTrue(page.hasColor(WorkoutColors.ENDING, 0.15f, 0.4f))
    }

    @Test
    fun workoutCompleteWithoutTrace() {
        val s = finished().let { f -> f.copy(history = f.history.map { it.copy(trace = emptyList()) }, normalizedPower = null) }
        val page = render("workout-complete-no-trace", s)
        assertDrawn(page)
        // nothing recorded: blocks tinted by their in-range share, no target-band blocks
        assertFalse(page.hasColor(WorkoutColors.BAND, 0.15f, 0.4f))
    }

    @Test
    fun workoutCompleteWithAPartialTrace() {
        // the extension restarted mid-workout: early intervals have no line
        val s = finished().let { f -> f.copy(history = f.history.mapIndexed { i, r -> if (i < 3) r.copy(trace = emptyList()) else r }) }
        val page = render("workout-complete-mixed", s)
        assertTrue(page.hasColor(WorkoutColors.BAND, 0.15f, 0.4f))
        assertTrue(page.hasColor(WorkoutColors.ENDING, 0.15f, 0.4f))
    }

    @Test
    fun workoutCompleteHasNoPowerRow() {
        val s = finished()
        val page = render("workout-complete-fields", s)
        // the POWER row's status box would sit in the top of the page
        assertFalse(page.hasColor(WorkoutColors.status(s.primary!!.status), 0f, 0.09f))
    }

    @Test
    fun workoutCompleteHeartRate() {
        val f = finished()
        val hr = f.copy(
            history = f.history.map { it.copy(kind = TargetKind.HEART_RATE) },
            primary = f.primary!!.copy(kind = TargetKind.HEART_RATE),
            normalizedPower = null,
        )
        assertDrawn(render("workout-complete-hr", hr))
    }

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

    // --------------------------------------------------------- interval list

    @Test
    fun pageShowsTheNextPeek() {
        val peek = render("page-list-peek", planned(300))
        assertDrawn(peek)
        // the first screen is today's page: only the strip under the fields may differ
        val page = render("page-list-none", planned(300).copy(next = null))
        val peekTop = peek.height - (SCREEN_W * 0.035f).toInt()
        for (y in 0 until peekTop) for (x in 0 until peek.width) {
            if (peek.getPixel(x, y) != page.getPixel(x, y)) throw AssertionError("first screen differs at $x,$y")
        }
        val strip = (peekTop until peek.height).flatMap { y -> (0 until peek.width).map { peek.getPixel(it, y) } }
        assertTrue("a peek under the fields", strip.any { it != WorkoutColors.PANEL })
    }

    @Test
    fun pageScrolledToNext() {
        val s = planned(300)
        val next = render("page-list-next", s, scrollPx = (SCREEN_H - RIDE_HEADER).toFloat())
        assertDrawn(next)
        val top = render("page-list-top", s)
        val differ = (0 until next.height step 4).count { y -> next.getPixel(SCREEN_W / 2, y) != top.getPixel(SCREEN_W / 2, y) }
        assertTrue("the list, not the first screen", differ > next.height / 16)
    }

    @Test
    fun pageScrolledToDone() {
        val s = planned(300)
        // interval 3 skipped: its row says so instead of a share
        val history = s.history.map { if (it.index == 2) it.copy(endedNaturally = false) else it }
        val view = pageView(s.copy(history = history)).also { it.listScroll.offsetPx = 1e6f }
        val done = draw("page-list-done", view)
        assertDrawn(done)
        // clamped to the end of the list
        assertTrue(view.listScroll.offsetPx in 1f..(SCREEN_H * 2f))
    }

    @Test
    fun finishedWorkoutListHasNoNext() {
        val s = planned(300).copy(complete = true, stepRemainingMs = 0, totalRemainingMs = 0)
        val drawer = IntervalListDrawer(Paint(), Paint(), Paint(), Paint())
        // whatever the tracker says, a finished workout's list is DONE alone
        val rows = drawer.height(SCREEN_W, SCREEN_H, s, s.next!!)
        assertEquals(rows, drawer.height(SCREEN_W, SCREEN_H, s, NextList.Unknown(0, 0)))
        assertEquals(rows, drawer.height(SCREEN_W, SCREEN_H, s, NextList.Planned(emptyList())))
        assertTrue(rows < drawer.height(SCREEN_W, SCREEN_H, s.copy(complete = false), NextList.Planned(emptyList())))
        assertDrawn(render("page-list-complete", s, scrollPx = (SCREEN_H - RIDE_HEADER).toFloat()))
    }

    @Test
    fun listFallbackRow() {
        val s = ridden(300).copy(next = NextList.Unknown(8, 8_640_000))
        assertDrawn(render("page-list-unknown", s, scrollPx = (SCREEN_H - RIDE_HEADER).toFloat()))
    }

    @Test
    fun aDragScrollsTheWorkoutPage() {
        val view = pageView(planned(300))
        drag(view, fromY = 600f, toY = 300f)
        assertTrue("scrolled ${view.listScroll.offsetPx}", view.listScroll.offsetPx > 200f)
    }

    @Test
    fun noListOnOtherPages() {
        // the full page grown by hand (DRAWER_ONLY): vertical drags keep their old meaning
        val settings = Settings(pageMode = PageMode.DRAWER_ONLY)
        val machine = PanelMachine().apply {
            onWorkoutStarted(PageMode.DRAWER_ONLY, autoOpenDrawer = true)
            expand(PageMode.DRAWER_ONLY)
        }
        val view = pageView(planned(300), settings, machine = machine)
        drag(view, fromY = 600f, toY = 300f)
        assertEquals(0f, view.listScroll.offsetPx)
        // and the setting off on the workout page
        val off = pageView(planned(300), Settings(intervalList = false))
        drag(off, fromY = 600f, toY = 300f)
        assertEquals(0f, off.listScroll.offsetPx)
    }

    // gesture tests at the Karoo 2's density (300 dpi ~ xhdpi): touch slop 16 px

    @Test
    @Config(qualifiers = "xhdpi")
    fun aSmallDragDoesNotToggleTheTarget() {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        // a plain tap on the target still toggles it
        val tapped = pageView(planned(300)).also { draw("page-list-tap", it) }
        touch(tapped, listOf(240f to 100f, 240f to 100f))
        assertEquals(TargetDisplay.NUMERIC, tapped.targetDisplay)
        // within the slop a touch is a tap; past it, it scrolls and is no tap — never both
        for (d in listOf(4, 13, slop - 1)) {
            val view = pageView(planned(300)).also { draw("page-list-tap", it) }
            touch(view, listOf(240f to 100f, 240f to 100f - d * 0.6f, 240f to 100f - d, 240f to 100f - d))
            assertEquals("$d px", 0f, view.listScroll.offsetPx)
            assertEquals("$d px", TargetDisplay.NUMERIC, view.targetDisplay)
        }
        for (d in listOf(slop + 2, slop + 10)) {
            val view = pageView(planned(300)).also { draw("page-list-tap", it) }
            touch(view, listOf(240f to 100f, 240f to 100f - d, 240f to 100f - d - 8, 240f to 100f - d - 8))
            assertEquals("$d px", 8f, view.listScroll.offsetPx)
            assertEquals("$d px", TargetDisplay.VISUAL, view.targetDisplay)
        }
    }

    @Test
    @Config(qualifiers = "xhdpi")
    fun aHorizontalSwipeWithVerticalDriftStillChangesThePage() {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        val view = pageView(planned(300)).also { draw("page-list-swipe", it) }
        val swipes = mutableListOf<Boolean>()
        view.onPageSwipe = { swipes += it }
        // the glove lands, drifts up past the slop, then swipes left
        val y = 400f - slop - 4
        touch(view, listOf(380f to 400f, 378f to y, 300f to y, 200f to y, 100f to y, 80f to y), stepMs = 20)
        assertEquals(listOf(true), swipes)
        assertEquals(0f, view.listScroll.offsetPx)
    }

    /** DOWN at the first point, MOVE through the rest, UP at the last; [stepMs] apart. */
    private fun touch(view: View, points: List<Pair<Float, Float>>, stepMs: Long = 30) {
        val base = nextGestureMs.also { nextGestureMs += 10_000 }
        points.forEachIndexed { i, (x, y) ->
            val action = when (i) {
                0 -> MotionEvent.ACTION_DOWN
                points.lastIndex -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val e = MotionEvent.obtain(base, base + i * stepMs, action, x, y, 0)
            view.onTouchEvent(e)
            e.recycle()
        }
    }

    /** A slow finger drag (no fling) from [fromY] to [toY] down the middle of the view. */
    private fun drag(view: View, fromY: Float, toY: Float) {
        val x = SCREEN_W / 2f
        val steps = 20
        val base = nextGestureMs.also { nextGestureMs += 10_000 }
        fun event(action: Int, t: Long, y: Float) = MotionEvent.obtain(base, base + t, action, x, y, 0).also {
            view.onTouchEvent(it)
            it.recycle()
        }
        event(MotionEvent.ACTION_DOWN, 0L, fromY)
        for (i in 1..steps) event(MotionEvent.ACTION_MOVE, i * 50L, fromY + (toY - fromY) * i / steps)
        event(MotionEvent.ACTION_UP, (steps + 10) * 50L, toY)
    }

    private companion object {
        /**
         * Gesture times grow across tests: Robolectric's pooled VelocityTracker shadow
         * keeps old movements' times after clear(), and an overlapping old gesture
         * would leak into this one's fling velocity.
         */
        var nextGestureMs = 1_000_000L

        const val SCREEN_W = 480
        const val SCREEN_H = 800
        const val RIDE_HEADER = 60
    }
}
