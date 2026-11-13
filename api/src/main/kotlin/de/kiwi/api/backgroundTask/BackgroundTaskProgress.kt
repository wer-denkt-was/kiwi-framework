package de.kiwi.api.backgroundTask

/**
 * Progress of a background task, reported by its handler.
 * @see BackgroundTaskContext.reportProgress
 */
data class BackgroundTaskProgress(
    /**
     * Overall progress of the task in percent (`0` to `100`)
     */
    val percent: Int,
    /**
     * Current stage of the task, if the handler is divided into stages
     * @see [BackgroundTaskStages]
     */
    val stage: Stage? = null,
) {
    init {
        require(percent in 0..100) { "Invalid progress percentage: $percent" }
    }

    /** Progress of the current stage of a task */
    data class Stage(
        /** Name of the stage */
        val name: String,
        /** Index of the stage (`0` for the first stage) */
        val index: Int,
        /** Number of stages of the task */
        val count: Int,
        /** Number of work items completed in this stage, `null` if the stage has no discrete work items */
        val completedItems: Int? = null,
        /** Total number of work items of this stage, `null` if the stage has no discrete work items */
        val totalItems: Int? = null,
    )

    companion object {
        /** Progress of a task that hasn't reported any progress yet */
        val NONE = BackgroundTaskProgress(percent = 0)
    }
}
