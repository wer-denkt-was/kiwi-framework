package de.kiwi.api.util

import org.springframework.boot.autoconfigure.condition.ConditionMessage
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.ConfigurationCondition
import org.springframework.core.type.AnnotatedTypeMetadata
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.cast

/**
 * Base class for conditions matching a configured provider property against the provider enum value [E] of a
 * conditional annotation [A], like `@ConditionalOnStorageProvider(StorageProvider.S3)`.
 *
 * - If the [providerProperty] is set, the condition matches if its value (bound with relaxed binding) equals the
 *   annotation's provider value.
 * - If the property is not set, but the application declares a bean of the [providedBeanClass], the condition does
 *   not match, so the application's bean is used instead.
 * - Otherwise, the property is required: the condition fails with an [InvalidConfigurationPropertyValueException],
 *   aborting the startup.
 *
 * The condition is evaluated when registering beans, so it only sees beans registered before, i.e. the beans of the
 * application when used in an autoconfiguration.
 * @param E Provider enum type
 * @param A Conditional annotation type, declaring the provider value in its [annotationProperty]
 */
abstract class AbstractProviderCondition<E : Enum<E>, A : Annotation> : SpringBootCondition(), ConfigurationCondition {
    /** Name of the configuration property selecting the provider, e.g. `kiwi.storage.provider` */
    protected abstract val providerProperty: String

    /** Enum class of the provider values */
    protected abstract val providerClass: KClass<out Enum<E>>

    /** Class of the conditional annotation */
    protected abstract val annotationClass: KClass<out A>

    /** Property of the conditional annotation declaring the matching provider value */
    protected abstract val annotationProperty: KProperty1<A, E>

    /** Type of a bean the application may declare instead of setting the provider property, or `null` */
    protected abstract val providedBeanClass: KClass<*>?

    override fun getConfigurationPhase() = ConfigurationCondition.ConfigurationPhase.REGISTER_BEAN

    override fun getMatchOutcome(context: ConditionContext, metadata: AnnotatedTypeMetadata): ConditionOutcome {
        val attributes = checkNotNull(metadata.getAnnotationAttributes(annotationClass.java.name))
        val matchingProvider = providerClass.cast(attributes.getValue(annotationProperty.name))

        val message = ConditionMessage.forCondition(annotationClass.java.name, matchingProvider)
        val configuredProvider = Binder.get(context.environment)
            .bind(providerProperty, providerClass.java)
            .orElse(null)

        return when {
            configuredProvider != null -> {
                val reason = message.because("$providerProperty is ${configuredProvider.name}")
                if (configuredProvider == matchingProvider) ConditionOutcome.match(reason)
                else ConditionOutcome.noMatch(reason)
            }

            hasProvidedBean(context) -> {
                val reason = message.because("$providerProperty is not set, using provided bean")
                ConditionOutcome.noMatch(reason)
            }

            else -> {
                val oneOf = providerClass.java.enumConstants.joinToString(", ") { it.name }
                val message = providedBeanClass
                    ?.let { "$providerProperty is required, one of: $oneOf; or declare a ${it.java.name} bean" }
                    ?: "$providerProperty is required, one of: $oneOf"

                throw InvalidConfigurationPropertyValueException(providerProperty, null, message)
            }
        }
    }

    private fun hasProvidedBean(context: ConditionContext): Boolean {
        val beanType = providedBeanClass?.java ?: return false
        val beanFactory = context.beanFactory ?: return false
        val availableBeanNames = beanFactory.getBeanNamesForType(
            /* type = */ beanType,
            /* includeNonSingletons = */ true,
            /* allowEagerInit = */ false,
        )
        return availableBeanNames.isNotEmpty()
    }
}
