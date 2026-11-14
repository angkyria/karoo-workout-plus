package com.angkyria.karooworkout.data

/**
 * Tells a new workout from the one going on, for the plan tracker and the
 * once-per-workout library read. A new generation starts on a Hidden frame after a
 * shown workout, a changed step count, demo mode toggled, or a new [WorkoutEngine.epoch]
 * (a workout the engine told apart while one stayed shown). Pure.
 */
class WorkoutGeneration {
    var value = 0
        private set

    /** The last frame showed a workout. */
    var shown = false
        private set
    private var stepCount: Int? = null
    private var demo = false
    private var epoch: Int? = null
    private var readValue = -1

    /** One frame; true when it starts a new generation. */
    fun update(state: WorkoutUiState, demoMode: Boolean, engineEpoch: Int): Boolean {
        val new = when {
            state !is WorkoutUiState.Shown -> shown || demo != demoMode
            !shown -> false
            else -> stepCount != state.stepCount || demo != demoMode || epoch != engineEpoch
        }
        shown = state is WorkoutUiState.Shown
        stepCount = (state as? WorkoutUiState.Shown)?.stepCount
        demo = demoMode
        epoch = engineEpoch
        if (new) value++
        return new
    }

    /** True once per generation, and only while the library is [enabled] (wanted at all). */
    fun needsRead(enabled: Boolean): Boolean {
        if (!enabled || readValue == value) return false
        readValue = value
        return true
    }
}
