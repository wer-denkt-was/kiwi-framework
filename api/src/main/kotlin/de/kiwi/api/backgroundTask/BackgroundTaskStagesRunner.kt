package de.kiwi.api.backgroundTask

import de.kiwi.api.domain.DomainEvent
import de.kiwi.api.domain.DomainResult
import tools.jackson.core.type.TypeReference
import java.util.*
import kotlin.math.floor

/**
 * Runs the [BackgroundTaskStages] of a task in two passes (planning and execution), as documented in
 * [BackgroundTaskStages]. Used by [BackgroundTaskContext] implementations to implement [BackgroundTaskContext.stages],
 * i.e. by the workers of the `kiwi-background-tasks` module, and by the [InMemoryBackgroundTaskContext] for tests.
 *
 * Checkpoints (stage results, completed items) are read from and written to the [scratchpad].
 * Each completed stage is passed to the [completion], storing its result checkpoint and publishing its events.
 * The reported progress doesn't drop below the [initialProgress] (retained from a previous attempt of a resumed task),
 * and stages skipped on resume don't report progress.
 */
class BackgroundTaskStagesRunner(
    /** Context of the task, receiving the progress, and checked for cancellation before each stage and item */
    private val context: BackgroundTaskContext,
    /** Progress of the task when the execution started, the minimum of the reported progress */
    private val initialProgress: BackgroundTaskProgress,
    /** Completion of each executed stage */
    private val completion: Completion,
) {
    private val scratchpad = context.scratchpad

    /** Completion of a stage executed by a [BackgroundTaskStagesRunner] */
    fun interface Completion {
        /**
         * Complete the stage of the given [stageName]: Store its [resultValue] in the scratchpad under the [resultKey]
         * (`null` if the stage isn't skippable, then the result isn't stored), and publish the [events] recorded by
         * the stage, in one transaction.
         */
        fun complete(
            stageName: String,
            resultKey: String?,
            resultValue: Any?,
            events: List<DomainEvent>,
        )
    }

    private data class PlannedStage(
        val name: String,
        val quota: Int,
        val skippable: Boolean,
    )

    private data class StagePlaceholder<T>(
        override val stageName: String,
    ) : BackgroundTaskStageResult<T> {
        override val value: T
            get() = throw StageResultUnavailable(stageName)
    }

    private data class StageValue<T>(
        override val stageName: String,
        override val value: T,
    ) : BackgroundTaskStageResult<T>

    /** Run the stages of the given [declaration], returning the result of the task */
    fun <T> runStages(declaration: BackgroundTaskStages.Declaration<T>): T {
        val plan = planStages(declaration)
        val result = executeStages(plan, declaration)
        return result.value
    }

    private fun <T> planStages(declaration: BackgroundTaskStages.Declaration<T>): List<PlannedStage> {
        val planning = PlanningPass()
        try {
            planning.declaration()
        } catch (ex: StageResultUnavailable) {
            throw IllegalStateException("Invalid stages of background task: ${ex.message}", ex)
        }
        return planning.stages.also { stages ->
            check(stages.isNotEmpty()) { "Background task declares no stages" }
        }
    }

    private fun <T> executeStages(
        plan: List<PlannedStage>,
        declaration: BackgroundTaskStages.Declaration<T>,
    ): BackgroundTaskStageResult<T> {
        val execution = ExecutionPass(plan)
        val result = execution.declaration()
        execution.requireAllStagesExecuted()
        return result
    }

    /** Planning pass: Records the declared stages */
    private class PlanningPass : BackgroundTaskStages() {
        val stages = mutableListOf<PlannedStage>()

        override fun <T> stage(
            name: String,
            skippable: Boolean,
            quota: Int,
            resultType: TypeReference<T>,
            block: BackgroundTaskStageBlock<T>,
        ): BackgroundTaskStageResult<T> {
            require(name.isNotBlank()) { "Stage name must not be blank" }
            require(quota > 0) { "Quota of stage [$name] must be positive: $quota" }
            require(stages.none { it.name == name }) { "Duplicate stage name: $name" }
            BackgroundTaskScratchpad.requireValidKey(stageResultKey(name))
            stages.add(PlannedStage(name, quota, skippable))
            return StagePlaceholder(name)
        }
    }

    private inner class ExecutionPass(
        private val plan: List<PlannedStage>,
    ) : BackgroundTaskStages() {
        private val progress = StagesProgress(plan)
        private var counter = 0

        override fun <T> stage(
            name: String,
            skippable: Boolean,
            quota: Int,
            resultType: TypeReference<T>,
            block: BackgroundTaskStageBlock<T>,
        ): BackgroundTaskStageResult<T> {
            // Assert consistency with planned stages
            val index = counter++
            val planned = plan.getOrNull(index)
            check(planned == PlannedStage(name, quota, skippable)) {
                "Stage [$name] (index=$index, quota=$quota, skippable=$skippable), inconsistent with planned stages: $plan"
            }

            // Ensure context is active before executing stages
            context.checkActive()

            // If stage is skippable and its key is present in the scratchpad,
            // skip the stage and return the previously stored result
            val resultKey = stageResultKey(name)
            val isUnitResult = resultType.type == Unit::class.java
            if (skippable && resultKey in scratchpad) {
                // Completed by a previous attempt: Restore the result instead of executing the stage again
                @Suppress("UNCHECKED_CAST")
                val value =
                    if (isUnitResult) Unit as T
                    else scratchpad.get(resultKey, resultType) as T

                progress.skipStage(index)
                return StageValue(name, value)
            }

            // Execute the stage by invoking its execution block
            progress.startStage(index)
            val runningStage = RunningStage(name, index)
            val resultValue = block.execute(runningStage)

            // Complete the stage: If it is skippable, store its result to skip it on later attempts,
            // and publish the events recorded by the stage (events of a stage that fails are discarded)
            completion.complete(
                stageName = name,
                resultKey = resultKey.takeIf { skippable },
                resultValue = resultValue.takeIf { skippable && !isUnitResult },
                events = runningStage.recordedEvents(),
            )
            progress.completeStage(index)
            return StageValue(name, resultValue)
        }

        fun requireAllStagesExecuted() {
            check(counter == plan.size) {
                val notExecuted = plan.drop(counter).map { it.name }
                "Stages were planned, but not executed: $notExecuted"
            }
        }

        private inner class RunningStage(
            override val name: String,
            override val index: Int,
        ) : BackgroundTaskStage() {
            /** Events recorded by the stage, published when the stage is completed */
            private val events: MutableList<DomainEvent> = Collections.synchronizedList(mutableListOf())

            fun recordedEvents(): List<DomainEvent> = synchronized(events) { events.toList() }

            override val context: BackgroundTaskContext
                get() = this@BackgroundTaskStagesRunner.context

            override fun totalItems(count: Int) {
                require(count >= 0) { "Invalid number of items: $count" }
                progress.setTotalItems(index, count)
            }

            override fun itemsCompleted(count: Int) {
                require(count >= 0) { "Invalid number of items: $count" }
                progress.addCompletedItems(index, count)
            }

            override fun fractionCompleted(fraction: Double) {
                require(fraction in 0.0..1.0) { "Invalid fraction: $fraction" }
                progress.setFraction(index, fraction)
            }

            override fun <I, R> mapEachItem(
                items: Collection<I>,
                mappedType: TypeReference<R>,
                itemKey: (I) -> String,
                action: (I) -> R,
            ): List<R> {
                val keyedItems = addKeyedItems(items, itemKey)
                val isUnitResult = mappedType.type == Unit::class.java

                val mappedResults = ArrayList<R>(items.size)
                for ((item, key) in keyedItems) {
                    val result = if (key in scratchpad) {
                        // Completed by a previous attempt: Restore the mapped value
                        @Suppress("UNCHECKED_CAST")
                        if (isUnitResult) Unit as R
                        else scratchpad.get(key, mappedType) as R
                    } else {
                        context.checkActive()
                        action(item).also { result ->
                            scratchpad.put(key, if (isUnitResult) null else result)
                            progress.addCompletedItems(index, 1)
                        }
                    }
                    mappedResults.add(result)
                }

                return mappedResults
            }

            override fun <I> forEachItem(
                items: Collection<I>,
                itemKey: (I) -> String,
                action: (I) -> Unit,
            ) {
                val keyedItems = addKeyedItems(items, itemKey)

                for ((item, key) in keyedItems) {
                    if (key in scratchpad) continue // completed by a previous attempt
                    context.checkActive()
                    action(item)
                    scratchpad.put(key, true)
                    progress.addCompletedItems(index, 1)
                }
            }

            /**
             * Pair the items with their scratchpad keys (validating the keys, which must be unique), and add them to
             * the total items of this stage, including the items completed by a previous attempt
             */
            private fun <I> addKeyedItems(items: Collection<I>, itemKey: (I) -> String): List<Pair<I, String>> {
                val keyedItems = items.map { item ->
                    val key = stageItemKey(name, itemKey(item))
                    BackgroundTaskScratchpad.requireValidKey(key)
                    item to key
                }
                val duplicateKeys = keyedItems.groupingBy { it.second }.eachCount().filterValues { it > 1 }.keys
                require(duplicateKeys.isEmpty()) { "Duplicate item keys in stage [$name]: $duplicateKeys" }

                val alreadyCompleted = keyedItems.count { (_, key) -> key in scratchpad }
                progress.addItems(index, total = items.size, completed = alreadyCompleted)
                return keyedItems
            }

            override fun record(event: DomainEvent) {
                events.add(event)
            }

            override fun <T> record(result: DomainResult<T>): T {
                events.addAll(result.events)
                return result.value
            }
        }
    }

    /**
     * Progress of the stages, reported to the context on each change.
     * The reported percentage doesn't drop below the [initialProgress].
     */
    private inner class StagesProgress(
        private val stages: List<PlannedStage>,
    ) {
        private val totalQuota = stages.sumOf { it.quota }
        private var index = -1
        private var completed = false
        private var completedItems: Int? = null
        private var totalItems: Int? = null
        private var fraction: Double? = null

        @Synchronized
        fun startStage(stageIndex: Int) {
            resetStage(stageIndex, isCompleted = false)
            report()
        }

        @Synchronized
        fun completeStage(stageIndex: Int) {
            check(stageIndex == index && !completed) { "Stage [${stages[stageIndex].name}] is not running" }
            completed = true
            totalItems?.let { completedItems = it }
            report()
        }

        /** Stage skipped, as it has been completed by a previous attempt: Not reported, covered by the initial progress */
        @Synchronized
        fun skipStage(stageIndex: Int) {
            resetStage(stageIndex, isCompleted = true)
        }

        @Synchronized
        fun setTotalItems(stageIndex: Int, count: Int) = update(stageIndex) {
            totalItems = count
            if (completedItems == null) completedItems = 0
        }

        @Synchronized
        fun addItems(stageIndex: Int, total: Int, completed: Int) = update(stageIndex) {
            totalItems = (totalItems ?: 0) + total
            completedItems = (completedItems ?: 0) + completed
        }

        @Synchronized
        fun addCompletedItems(stageIndex: Int, count: Int) = update(stageIndex) {
            completedItems = (completedItems ?: 0) + count
        }

        @Synchronized
        fun setFraction(stageIndex: Int, value: Double) = update(stageIndex) {
            fraction = value
        }

        private inline fun update(stageIndex: Int, change: () -> Unit) {
            check(stageIndex == index && !completed) { "Stage [${stages[stageIndex].name}] is not running" }
            change()
            report()
        }

        private fun resetStage(stageIndex: Int, isCompleted: Boolean) {
            index = stageIndex
            completed = isCompleted
            completedItems = null
            totalItems = null
            fraction = null
        }

        private fun report() {
            // Get current stage, completed quota
            val stage = stages[index]
            val completedQuota = stages.take(index).sumOf { it.quota }

            // Calculate fraction of current stage
            val iFraction = fraction
            val iTotalItems = totalItems
            val iCompletedItems = completedItems ?: 0
            val stageFraction = when {
                completed -> 1.0
                iFraction != null -> iFraction
                iTotalItems != null && iTotalItems > 0 -> iCompletedItems.toDouble() / iTotalItems
                else -> 0.0
            }.coerceIn(0.0, 1.0)

            // Calculate current progress state
            val currentFraction = (completedQuota + stage.quota * stageFraction) / totalQuota
            val currentPercent = floor(100.0 * currentFraction).toInt()
                .coerceIn(initialProgress.percent, 100) // don't drop below the progress of a previous attempt

            val progressStage = BackgroundTaskProgress.Stage(
                name = stage.name,
                index = index,
                count = stages.size,
                completedItems = completedItems,
                totalItems = totalItems,
            )

            // Report progress to task context
            context.reportProgress(BackgroundTaskProgress(currentPercent, progressStage))
        }
    }
}

/** Thrown when accessing a [BackgroundTaskStageResult] in the planning pass of [BackgroundTaskStagesRunner] */
private class StageResultUnavailable(stageName: String) : IllegalStateException(
    buildString {
        append("Result of stage [$stageName] is only available within the stages declared after it:")
        append(" Don't access stage results outside of stage lambdas,")
        append(" and return the BackgroundTaskStageResult instead of its value")
    },
)
