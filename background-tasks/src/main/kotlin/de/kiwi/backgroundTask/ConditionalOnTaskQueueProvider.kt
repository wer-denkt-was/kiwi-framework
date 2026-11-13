package de.kiwi.backgroundTask

import de.kiwi.api.util.AbstractProviderCondition
import de.kiwi.backgroundTask.KiwiBackgroundTaskProperties.TaskQueueProvider
import de.kiwi.backgroundTask.application.BackgroundTaskQueue
import org.springframework.context.annotation.Conditional

/**
 * Condition matching if the configured `kiwi.background-task.queue.provider` is the given [TaskQueueProvider].
 * The property is bound like [KiwiBackgroundTaskProperties.TaskQueue.provider] (relaxed binding).
 *
 * The property is required, unless the application declares its own [BackgroundTaskQueue] bean: if it's missing, the
 * condition fails with an `InvalidConfigurationPropertyValueException`, aborting the startup. Like
 * `@ConditionalOnMissingBean`, the condition only sees beans registered before the background task
 * autoconfiguration, i.e. the beans of the application.
 *
 * @see de.kiwi.api.util.AbstractProviderCondition
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Conditional(OnTaskQueueProviderCondition::class)
annotation class ConditionalOnTaskQueueProvider(
    /** Queue provider the condition matches */
    val value: TaskQueueProvider,
)

internal class OnTaskQueueProviderCondition :
    AbstractProviderCondition<TaskQueueProvider, ConditionalOnTaskQueueProvider>() {

    override val providerProperty = KiwiBackgroundTaskProperties.QUEUE_PROVIDER
    override val providerClass = TaskQueueProvider::class
    override val annotationClass = ConditionalOnTaskQueueProvider::class
    override val annotationProperty = ConditionalOnTaskQueueProvider::value
    override val providedBeanClass = BackgroundTaskQueue::class
}
