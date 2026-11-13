package de.kiwi.backgroundTask

import de.kiwi.api.util.AbstractProviderCondition
import de.kiwi.backgroundTask.KiwiBackgroundTaskProperties.TaskMessagingProvider
import de.kiwi.backgroundTask.application.messaging.BackgroundTaskMessaging
import org.springframework.context.annotation.Conditional

/**
 * Condition matching if the configured `kiwi.background-task.messaging.provider` is the given [TaskMessagingProvider].
 * The property is bound like [KiwiBackgroundTaskProperties.TaskMessaging.provider] (relaxed binding).
 *
 * The property is required, unless the application declares its own [BackgroundTaskMessaging] bean: if it's missing,
 * the condition fails with an `InvalidConfigurationPropertyValueException`, aborting the startup. Like
 * `@ConditionalOnMissingBean`, the condition only sees beans registered before the background task
 * autoconfiguration, i.e. the beans of the application.
 *
 * @see de.kiwi.api.util.AbstractProviderCondition
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Conditional(OnTaskMessagingProviderCondition::class)
annotation class ConditionalOnTaskMessagingProvider(
    /** Messaging provider the condition matches */
    val value: TaskMessagingProvider,
)

internal class OnTaskMessagingProviderCondition :
    AbstractProviderCondition<TaskMessagingProvider, ConditionalOnTaskMessagingProvider>() {

    override val providerProperty = KiwiBackgroundTaskProperties.MESSAGING_PROVIDER
    override val providerClass = TaskMessagingProvider::class
    override val annotationClass = ConditionalOnTaskMessagingProvider::class
    override val annotationProperty = ConditionalOnTaskMessagingProvider::value
    override val providedBeanClass = BackgroundTaskMessaging::class
}
