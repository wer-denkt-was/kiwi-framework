package de.kiwi.storage

import de.kiwi.api.application.service.FileStorageService
import de.kiwi.api.util.AbstractProviderCondition
import de.kiwi.storage.KiwiStorageProperties.StorageProvider
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException
import org.springframework.context.annotation.Conditional

/**
 * Condition matching if the configured `kiwi.storage.provider` is the given [StorageProvider].
 * The property is bound like [KiwiStorageProperties.provider], i.e. `data-uri`, `DataUri` and `DATA_URI` are equal.
 *
 * The property is required, unless the application declares its own [FileStorageService] bean: if it's missing, the
 * condition fails with an [InvalidConfigurationPropertyValueException], aborting the startup before any bean is
 * created. Like `@ConditionalOnMissingBean`, the condition is evaluated when registering beans, so it only sees beans
 * registered before the Kiwi storage autoconfiguration, i.e. the beans of the application.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Conditional(OnStorageProviderCondition::class)
annotation class ConditionalOnStorageProvider(val value: StorageProvider)

internal class OnStorageProviderCondition :
    AbstractProviderCondition<StorageProvider, ConditionalOnStorageProvider>() {

    override val providerProperty = "kiwi.storage.provider"
    override val providerClass = StorageProvider::class
    override val annotationClass = ConditionalOnStorageProvider::class
    override val annotationProperty = ConditionalOnStorageProvider::value
    override val providedBeanClass = FileStorageService::class
}
