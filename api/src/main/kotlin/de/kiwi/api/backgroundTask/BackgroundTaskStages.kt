package de.kiwi.api.backgroundTask

import de.kiwi.api.domain.DomainEvent
import de.kiwi.api.domain.DomainResult
import tools.jackson.core.type.TypeReference

/**
 * Runs a [BackgroundTaskHandler] divided into stages, tracking the task's progress based on the stages and their work
 * items, and storing checkpoints in the task's [scratchpad][BackgroundTaskContext.scratchpad], so a resumed task skips
 * completed stages and work items.
 *
 * For example:
 * ```kotlin
 * override fun handle(request: CopyFilesRequest, context: BackgroundTaskContext) = context.stages {
 *     val files = stage("gather", quota = 1) {
 *         gatherFiles(request.fromPath)
 *     }
 *     val copied = stage("copy", quota = 8) { stage ->
 *         stage.mapEachItem(files.value, itemKey = { it.name }) { file ->
 *             copyFile(file, request.toPath)
 *         }
 *     }
 *     stage("verify", quota = 1) {
 *         verifyFiles(files.value, copied.value)
 *     }
 * }
 * ```
 *
 * Each stage has a `quota`, its share of the total progress relative to the other stages.
 * Within a stage, progress is derived from its work items, or reported as a fraction;
 * otherwise the stage is indeterminate until it is completed.
 *
 * Item-based progress/processing methods include:
 * - [BackgroundTaskStage.totalItems], report total items
 * - [BackgroundTaskStage.itemsCompleted], report completion of 1 or more items
 * - [BackgroundTaskStage.forEachItem], iterate items (skipping previously completed items)
 * - [BackgroundTaskStage.mapEachItem], iterate items and map results (skipping previously mapped items)
 * - [BackgroundTaskStage.fractionCompleted], report completion fraction not based on items
 *
 * The `block` is invoked twice:
 * 1. Planning: The stages are recorded, but not executed.
 *    The returned [BackgroundTaskStageResult]s are placeholders,
 *    accessing their [value][BackgroundTaskStageResult.value] is guaranteed to fail.
 * 2. Execution: The stages are executed in order, their results are available to later stages.
 *
 * Therefore, the block *must only* declare stages: Code outside the stage lambdas runs twice, and the stages must not
 * depend on stage results (e.g. by declaring them conditionally). Stage results must be serializable as JSON, as they
 * are stored as checkpoints; a resumed task restores the results of completed stages instead of executing them again.
 *
 * The result of the block is the result of the task; the returned [BackgroundTaskStageResult] of the last stage is
 * unwrapped.
 *
 * Domain events recorded by a stage ([BackgroundTaskStage.record]) are published when the stage is completed, on behalf
 * of the user that launched the task, in the same transaction that stores the stage's result checkpoint: The events of
 * a completed stage are published exactly once, even if the task is resumed. The events of a stage that fails are
 * discarded. The returned [DomainResult] contains no events, as they have been published already.
 *
 * A resumed task reports its progress starting from the progress of the previous attempt: Skipped stages don't report
 * progress, and the reported percentage doesn't drop below the progress of the previous attempt.
 */
abstract class BackgroundTaskStages {
    /**
     * Function block declaring [BackgroundTaskStages] using the [stage][BackgroundTaskStages.stage] DSL
     * @see BackgroundTaskStages
     */
    typealias Declaration<T> = BackgroundTaskStages.() -> BackgroundTaskStageResult<T>

    /**
     * Declare a stage with the given unique [name] and [quota] (its share of the total progress, relative to the other
     * stages), executing the [block] (in the execution pass).
     * If the stage is declared [skippable], it will be skipped if it has previously completed in an earlier attempt.
     * The result type for serializing its checkpoint is passed as a Jackson [TypeReference].
     * @return Result of the stage, available to later stages
     * @see BackgroundTaskStages
     */
    abstract fun <T> stage(
        name: String,
        skippable: Boolean,
        quota: Int,
        resultType: TypeReference<T>,
        block: BackgroundTaskStageBlock<T>,
    ): BackgroundTaskStageResult<T>

    /**
     * Declare a stage with the given unique [name] and [quota] (its share of the total progress, relative to the other
     * stages), executing the [block] (in the execution pass).
     * If the stage is declared [skippable], it will be skipped if it has previously completed in an earlier attempt.
     * The result type for serializing its checkpoint is inferred from [T].
     * @return Result of the stage, available to later stages
     * @see BackgroundTaskStages
     */
    inline fun <reified T> stage(
        name: String,
        skippable: Boolean,
        quota: Int = 1,
        block: BackgroundTaskStageBlock<T>,
    ): BackgroundTaskStageResult<T> {
        return stage(
            name = name,
            skippable = skippable,
            quota = quota,
            resultType = typeReferenceOf<T>(),
            block = block,
        )
    }

    /**
     * Declare a *skippable* stage with the given [name] and [quota], see [stage]
     * @see stage
     */
    inline fun <reified T> optionalStage(
        name: String,
        quota: Int = 1,
        block: BackgroundTaskStageBlock<T>,
    ) = stage(name, skippable = true, quota, block)

    /**
     * Declare a *non-skippable* stage with the given [name] and [quota], see [stage]
     * @see stage
     */
    inline fun <reified T> requiredStage(
        name: String,
        quota: Int = 1,
        block: BackgroundTaskStageBlock<T>,
    ) = stage(name, skippable = false, quota, block)

    companion object {
        /** Scratchpad key of the result of the given stage: `kiwi:stage:<stageName>:result` */
        fun stageResultKey(stageName: String) = "kiwi:stage:${stageName}:result"

        /** Scratchpad key of a completed work item of the given stage: `kiwi:stage:<stageName>:item:<itemKey>` */
        fun stageItemKey(stageName: String, itemKey: String) = "kiwi:stage:${stageName}:item:${itemKey}"
    }
}

/**
 * Stage of a task declared in [BackgroundTaskStages], passed to its [BackgroundTaskStageBlock] while it is executed:
 * Reports the progress of the stage, processes its work items with checkpoints, and records its domain events.
 *
 * Progress is reported either based on work items ([totalItems] and [itemsCompleted], or [forEachItem] and
 * [mapEachItem]), or as a fraction ([fractionCompleted]); a stage reporting neither is indeterminate until it is
 * completed. The stage must only be used while it is executed: Reporting progress afterward throws an
 * [IllegalStateException].
 */
abstract class BackgroundTaskStage {
    /** Name of the stage */
    abstract val name: String

    /** Index of the stage (`0` for the first stage) */
    abstract val index: Int

    /** Context of the task */
    abstract val context: BackgroundTaskContext

    /** Set the total number of work items of this stage, making its progress determinate */
    abstract fun totalItems(count: Int)

    /** Record the completion of the given number of work items */
    abstract fun itemsCompleted(count: Int = 1)

    /** Report the progress of this stage as a fraction (`0.0` to `1.0`), for stages without discrete work items */
    abstract fun fractionCompleted(fraction: Double)

    /**
     * Process the given work [items], identified by their unique [itemKey]: Adds the items to the total items of this
     * stage, skips the items completed by a previous attempt, and stores a checkpoint with their mapped value
     * after each processed item. Returns a list of mapped values corresponding to the items.
     * The [mappedType] (TypeReference<[R]>) must be serializable, as mapping results are written to the scratchpad.
     * Checks whether the task is still active before each item ([BackgroundTaskContext.checkActive]).
     * @throws IllegalArgumentException if the item keys are not unique (before processing any item)
     */
    abstract fun <I, R> mapEachItem(
        items: Collection<I>,
        mappedType: TypeReference<R>,
        itemKey: (I) -> String,
        action: (I) -> R,
    ): List<R>

    /**
     * Process the given work [items], identified by their unique [itemKey]: Adds the items to the total items of this
     * stage, skips the items completed by a previous attempt, and stores a checkpoint with their mapped value
     * after each processed item. Returns a list of mapped values corresponding to the items.
     * The mapped type (inferred from [R]) must be serializable, as mapping results are written to the scratchpad.
     * Checks whether the task is still active before each item ([BackgroundTaskContext.checkActive]).
     * @throws IllegalArgumentException if the item keys are not unique (before processing any item)
     */
    inline fun <I, reified R> mapEachItem(
        items: Collection<I>,
        noinline itemKey: (I) -> String,
        noinline action: (I) -> R,
    ): List<R> {
        val mappedType: TypeReference<R> = typeReferenceOf<R>()
        return mapEachItem(items, mappedType, itemKey, action)
    }

    /**
     * Process the given work [items], identified by their unique [itemKey]: Adds the items to the total items of this
     * stage, skips the items completed by a previous attempt, and stores a checkpoint after each processed item.
     * Checks whether the task is still active before each item ([BackgroundTaskContext.checkActive]).
     * @throws IllegalArgumentException if the item keys are not unique (before processing any item)
     */
    abstract fun <I> forEachItem(
        items: Collection<I>,
        itemKey: (I) -> String,
        action: (I) -> Unit,
    )

    /**
     * Record a domain event, published when the stage is completed (discarded if the stage fails).
     *
     * Don't record events per item in [forEachItem] or [mapEachItem]: Items are checkpointed individually, so if the
     * stage fails after some items, their events are discarded, but the items are skipped when the task is resumed,
     * and their events are never published. Record events once per stage instead (e.g. a summary event).
     * @see BackgroundTaskStages
     */
    abstract fun record(event: DomainEvent)

    /** Record the events of the given [DomainResult] (see [record]), returning its value */
    abstract fun <R> record(result: DomainResult<R>): R
}

/**
 * Execution block of a stage declared in [BackgroundTaskStages]
 * @see BackgroundTaskStages
 */
fun interface BackgroundTaskStageBlock<T> {
    /**
     * Execute the given [stage], returning its result (serializable as JSON, if the stage is skippable)
     */
    fun execute(stage: BackgroundTaskStage): T
}

/** Result of a stage declared in [BackgroundTaskStages] */
interface BackgroundTaskStageResult<T> {
    /** Name of the stage that produced the result */
    val stageName: String

    /**
     * Result value of the stage. *Only* available within the stages declared after this stage:
     * Accessing it in the planning pass will fail with an exception.
     * @throws IllegalStateException
     */
    val value: T
}

@PublishedApi
internal inline fun <reified R> typeReferenceOf(): TypeReference<R> {
    return object : TypeReference<R>() {}
}
