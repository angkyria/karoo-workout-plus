package com.angkyria.karooworkout

import android.util.Log
import com.angkyria.karooworkout.data.CoreHeat
import com.angkyria.karooworkout.data.RiderProfile
import com.angkyria.karooworkout.data.StreamHub
import com.angkyria.karooworkout.data.WorkoutEngine
import com.angkyria.karooworkout.data.WorkoutPage
import com.angkyria.karooworkout.data.WorkoutStreams
import com.angkyria.karooworkout.data.WorkoutUiState
import com.angkyria.karooworkout.debug.DemoWorkout
import com.angkyria.karooworkout.debug.Diagnostics
import com.angkyria.karooworkout.overlay.Format
import com.angkyria.karooworkout.overlay.OverlayController
import com.angkyria.karooworkout.settings.PageMode
import com.angkyria.karooworkout.settings.Settings
import com.angkyria.karooworkout.settings.SettingsRepo
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.ActiveRidePage
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Extension service bound by Karoo OS. Hosts the KarooSystemService connection,
 * the workout streams, the engine and the overlay that replaces the workout page.
 */
class WorkoutExtension : KarooExtension(EXTENSION_ID, BuildConfig.VERSION_NAME) {

    lateinit var karooSystem: KarooSystemService
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settingsRepo: SettingsRepo
    private lateinit var overlay: OverlayController
    private lateinit var hub: StreamHub
    private val engine = WorkoutEngine()
    private val demo = DemoWorkout()

    private val rideState = MutableStateFlow<RideState>(RideState.Idle)
    private val profile = MutableStateFlow(RiderProfile())
    private val demoValues = MutableStateFlow<Map<String, Map<String, Double>>>(emptyMap())
    private val fieldText = MutableStateFlow(IDLE_FIELD_TEXT)
    private val consumerIds = mutableListOf<String>()

    private var settings = Settings()
    private var lastPageMode: PageMode? = null
    private var lastStreamLogMs = 0L

    /** At least one RideState event arrived (the flow's initial Idle is just a default). */
    @Volatile private var rideStateKnown = false

    /** We watched the workout begin, so Karoo's silent jump to its workout page happened. */
    private var freshStartArmed = false

    override val types by lazy { listOf(WorkoutPageField(extension, fieldText)) }

    private val markerId: String get() = DataType.dataTypeId(extension, WorkoutPageField.TYPE_ID)

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
        settingsRepo = SettingsRepo(applicationContext)
        overlay = OverlayController(this)
        hub = StreamHub(karooSystem)

        karooSystem.connect { connected ->
            Log.i(TAG, "Karoo system connected=$connected hardware=${karooSystem.hardwareType}")
            if (connected) Diagnostics.hardware.value = karooSystem.hardwareType?.name
        }
        consumerIds += karooSystem.addConsumer<RideState> {
            rideStateKnown = true
            rideState.value = it
        }
        consumerIds += karooSystem.addConsumer<UserProfile> { p ->
            profile.value = RiderProfile(
                ftp = p.ftp.takeIf { it > 0 },
                maxHr = p.maxHr.takeIf { it > 0 },
                powerZones = p.powerZones.map { it.min..it.max },
                hrZones = p.heartRateZones.map { it.min..it.max },
                imperial = p.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL,
            )
        }
        // The visible ride page drives the takeover. Every event goes through (no
        // dedup): the machine may have *assumed* the workout page after Karoo's
        // silent jump to it, and must hear about leaving it.
        consumerIds += karooSystem.addConsumer<ActiveRidePage> { event ->
            val ids = event.page.elements.map { it.dataTypeId }
            val workoutPage = WorkoutPage.isWorkoutPage(ids, markerId)
            Log.d(TAG, "page workout=$workoutPage map=${event.page.mapPage} elements=$ids")
            Diagnostics.page.value = ids to workoutPage
            scope.launch { overlay.onPageChanged(event.page, workoutPage, settings.pageMode) }
        }

        // The workout gate stream runs for the whole ride (cheap, one stream);
        // everything else only while a workout is actually loaded.
        scope.launch {
            combine(settingsRepo.settings, rideState) { s, ride -> !s.demoMode && ride.active }
                .distinctUntilChanged()
                .collect { needed ->
                    hub.want(OWNER_GATE, if (needed) setOf(WorkoutStreams.GATE) else emptySet())
                }
        }
        // plus the rider's sensor for each targeted kind, in case Karoo gives no output
        scope.launch {
            combine(hub.values, settingsRepo.settings) { values, s ->
                val snapshot = WorkoutStreams.snapshot(values)
                if (!snapshot.loaded) {
                    emptySet()
                } else {
                    WorkoutStreams.DETAIL.toSet() +
                        WorkoutStreams.liveStreams(snapshot.kindValues.keys, s.smoothedOutput)
                }
            }
                .distinctUntilChanged()
                .collect { hub.want(OWNER_DETAIL, it) }
        }
        // page data fields stream only while the full page that draws them is up
        scope.launch {
            combine(settingsRepo.settings, overlay.fieldsVisible) { s, visible ->
                if (!visible) {
                    emptySet()
                } else {
                    s.pageFields.flatMap { listOfNotNull(it.dataTypeId, it.extraDataTypeId) }.toSet() +
                        (if (s.coreHeatStrip) CoreHeat.STRIP_STREAMS else emptySet())
                }
            }
                .distinctUntilChanged()
                .collect { hub.want(OWNER_FIELDS, it) }
        }
        // Demo mode: a synthetic workout at real time; collectLatest cancels the
        // ticker whenever demo mode is off, so real rides get no extra wakeups.
        scope.launch {
            settingsRepo.settings
                .map { it.demoMode }
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    demoValues.value = emptyMap()
                    if (enabled) demo.restart()
                    while (enabled) {
                        demoValues.value = demo.raw()
                        delay(1000)
                        demo.tick(1000)
                    }
                }
        }

        scope.launch {
            combine(hub.values, demoValues, settingsRepo.settings, rideState, profile) { values, demoRaw, s, ride, prof ->
                Inputs(values, demoRaw, s, ride, prof)
            }
                .conflate()
                .collect {
                    render(it)
                    // streams tick at different moments: render at most a few times a second
                    delay(MIN_FRAME_MS)
                }
        }
    }

    private data class Inputs(
        val values: Map<String, Map<String, Double>>,
        val demoRaw: Map<String, Map<String, Double>>,
        val settings: Settings,
        val ride: RideState,
        val profile: RiderProfile,
    )

    private fun render(i: Inputs) {
        val s = i.settings
        settings = s
        if (lastPageMode != null && lastPageMode != s.pageMode) overlay.onModeChanged(s)
        lastPageMode = s.pageMode

        val now = System.currentTimeMillis()
        val raw = if (s.demoMode) i.demoRaw else i.values
        val state = when {
            s.demoMode -> engine.update(WorkoutStreams.snapshot(raw), now, paused = false, s.smoothedOutput)
            !i.ride.active -> {
                engine.reset()
                WorkoutUiState.Hidden
            }
            else -> engine.update(WorkoutStreams.snapshot(raw), now, i.ride is RideState.Paused, s.smoothedOutput)
        }
        val workoutRaw = raw.filterKeys { it.startsWith(WORKOUT_TYPE_PREFIX) || it in WorkoutStreams.LIVE }
        Diagnostics.workoutStreams.value = workoutRaw
        if (!s.demoMode && workoutRaw.isNotEmpty() && now - lastStreamLogMs >= STREAM_LOG_INTERVAL_MS) {
            // field units / enum codes are undocumented: keep a trail for checking on device
            lastStreamLogMs = now
            Log.d(TAG, "streams $workoutRaw")
        }
        fieldText.value = (state as? WorkoutUiState.Shown)?.let(::fieldTextFor) ?: IDLE_FIELD_TEXT

        // Arm the "fresh start" only on a no-workout state we actually observed (ride
        // known idle, or the gate streaming without a workout) — not the blank state
        // of a service that just (re)started mid-workout.
        val observed = s.demoMode || (rideStateKnown && (!i.ride.active || WorkoutStreams.GATE in i.values))
        val freshStart = freshStartArmed
        if (state is WorkoutUiState.Shown) {
            freshStartArmed = false
        } else if (observed) {
            freshStartArmed = true
        }

        if (state is WorkoutUiState.Shown && s.overlayEnabled) {
            // demo adds a synthetic CORE reading; a real sensor's values win
            val sysValues = if (s.demoMode) demo.coreHeat() + i.values else i.values
            overlay.show(state, s, i.profile, sysValues, freshStart)
        } else {
            overlay.hide()
        }
    }

    /** What the Workout+ ride-page field shows beneath the overlay. */
    private fun fieldTextFor(s: WorkoutUiState.Shown): String {
        val time = s.stepRemainingMs?.let { Format.countdown(it) } ?: "OPEN"
        return "${s.stepIndex + 1}/${s.stepCount}  $time"
    }

    private val RideState.active: Boolean
        get() = this is RideState.Recording || this is RideState.Paused

    override fun onDestroy() {
        overlay.hide()
        consumerIds.forEach { karooSystem.removeConsumer(it) }
        hub.clear()
        scope.cancel()
        karooSystem.disconnect()
        super.onDestroy()
    }

    companion object {
        const val EXTENSION_ID = "karoo-workout"
        private const val TAG = "WorkoutExtension"
        private const val OWNER_GATE = "gate"
        private const val OWNER_DETAIL = "detail"
        private const val OWNER_FIELDS = "fields"
        private const val WORKOUT_TYPE_PREFIX = "TYPE_WORKOUT_"
        private const val IDLE_FIELD_TEXT = "WORKOUT+"
        private const val MIN_FRAME_MS = 250L
        private const val STREAM_LOG_INTERVAL_MS = 10_000L
    }
}
