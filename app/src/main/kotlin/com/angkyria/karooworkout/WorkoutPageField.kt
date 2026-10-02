package com.angkyria.karooworkout

import android.content.Context
import android.util.TypedValue
import android.widget.RemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * "Workout+" ride-page field. Put it on a page (best: alone, full page) and
 * Workout+ takes that page over with the workout layout whenever a workout runs.
 * Underneath the overlay it shows the interval countdown, so the page still
 * makes sense with the overlay disabled.
 */
class WorkoutPageField(
    extension: String,
    private val text: StateFlow<String>,
) : DataTypeImpl(extension, TYPE_ID) {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            // karoo-ext drops view updates closer than ~1 s apart; a StateFlow collector
            // that waits out that window always resumes with the latest text
            text.collect { value ->
                val views = RemoteViews(context.packageName, R.layout.field_workout_page).apply {
                    setTextViewText(R.id.field_text, value)
                    setTextViewTextSize(R.id.field_text, TypedValue.COMPLEX_UNIT_SP, config.textSize.toFloat())
                }
                emitter.updateView(views)
                delay(VIEW_UPDATE_INTERVAL_MS)
            }
        }
        emitter.setCancellable { scope.cancel() }
    }

    companion object {
        const val TYPE_ID = "workout-page"
        private const val VIEW_UPDATE_INTERVAL_MS = 1_000L
    }
}
