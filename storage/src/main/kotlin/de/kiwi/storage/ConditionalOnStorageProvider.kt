package de.kiwi.storage

import de.kiwi.api.application.service.FileStorageService
import de.kiwi.storage.KiwiStorageProperties.StorageProvider
import org.springframework.beans.factory.getBeanNamesForType
import org.springframework.boot.autoconfigure.condition.ConditionMessage
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.ConfigurationCondition
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase
import org.springframework.core.type.AnnotatedTypeMetadata

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

private const val STORAGE_PROVIDER_PROPERTY = "kiwi.storage.provider"

internal class OnStorageProviderCondition : SpringBootCondition(), ConfigurationCondition {

    override fun getConfigurationPhase() = ConfigurationPhase.REGISTER_BEAN

    override fun getMatchOutcome(context: ConditionContext, metadata: AnnotatedTypeMetadata): ConditionOutcome {
        val attributes = checkNotNull(metadata.getAnnotationAttributes(ConditionalOnStorageProvider::class.java.name))
        val requiredProvider = attributes["value"] as StorageProvider

        val message = ConditionMessage.forCondition(ConditionalOnStorageProvider::class.java, requiredProvider)
        val configuredProvider = Binder.get(context.environment)
            .bind(STORAGE_PROVIDER_PROPERTY, StorageProvider::class.java)
            .orElse(null)

        return when {
            configuredProvider != null -> {
                val reason = message.because("$STORAGE_PROVIDER_PROPERTY is $configuredProvider")
                if (configuredProvider == requiredProvider) ConditionOutcome.match(reason)
                else ConditionOutcome.noMatch(reason)
            }

            hasFileStorageServiceBean(context) -> {
                ConditionOutcome.noMatch(message.because("$STORAGE_PROVIDER_PROPERTY is not set, using own bean"))
            }

            else -> {
                val oneOf = StorageProvider.entries.joinToString(", ") { it.name }
                val message =
                    "The $STORAGE_PROVIDER_PROPERTY is required, one of: $oneOf; or declare a FileStorageService bean"
                throw InvalidConfigurationPropertyValueException(STORAGE_PROVIDER_PROPERTY, null, message)
            }
        }
    }

    /** Whether a [FileStorageService] bean is declared, without initializing any bean */
    private fun hasFileStorageServiceBean(context: ConditionContext): Boolean = context.beanFactory
        ?.getBeanNamesForType<FileStorageService>(includeNonSingletons = true, allowEagerInit = false)
        ?.isNotEmpty()
        ?: false
}
