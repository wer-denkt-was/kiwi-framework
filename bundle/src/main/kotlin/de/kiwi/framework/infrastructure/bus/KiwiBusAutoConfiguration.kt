package de.kiwi.framework.infrastructure.bus

import de.kiwi.api.application.command.CommandBus
import de.kiwi.api.application.event.EventBus
import de.kiwi.api.application.query.QueryBus
import de.kiwi.api.application.service.AuditLoggerService
import de.kiwi.api.application.service.CommandQueryBus
import de.kiwi.api.application.service.TransactionService
import de.kiwi.framework.KiwiFrameworkAutoConfiguration
import de.kiwi.framework.KiwiFrameworkProperties
import de.kiwi.framework.infrastructure.bus.command.*
import de.kiwi.framework.infrastructure.bus.event.AuditingEventBus
import de.kiwi.framework.infrastructure.bus.event.BasicEventBus
import de.kiwi.framework.infrastructure.bus.event.BeanAwareEventBus
import de.kiwi.framework.infrastructure.bus.event.RecursiveEventBus
import de.kiwi.framework.infrastructure.bus.query.AuthorizedQueryBus
import de.kiwi.framework.infrastructure.bus.query.BasicQueryBus
import de.kiwi.framework.infrastructure.bus.query.BeanAwareQueryBus
import de.kiwi.framework.infrastructure.bus.query.TransactionalQueryBus
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import tools.jackson.databind.ObjectMapper

/**
 * Autoconfiguration of the Kiwi [EventBus], [CommandBus] and [QueryBus].
 * Each bus can be replaced by declaring a bean of the same type.
 * Note that the [CommandQueryBus] bean is a [CommandBus] and a [QueryBus] as well, delegating to the actual buses.
 */
@AutoConfiguration(after = [KiwiFrameworkAutoConfiguration::class])
@EnableConfigurationProperties(KiwiFrameworkProperties::class)
class KiwiBusAutoConfiguration(
    private val props: KiwiFrameworkProperties,
    private val objectMapperProvider: ObjectProvider<ObjectMapper>,
    private val auditLoggerServiceProvider: ObjectProvider<AuditLoggerService>,
    private val transactionServiceProvider: ObjectProvider<TransactionService>,
) {
    /**
     * [EventBus] structural implementation:
     * ```
     * BeanAwareEventBus {
     *   RecursiveEventBus {
     *     (recursion) ->
     *     AuditingEventBus {
     *       (auditLog) ->
     *       BasicEventBus {
     *         -> (handle) ->
     *       }
     *     }
     *   }
     * }
     * ```
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean
    fun eventBus(): EventBus {
        val eventProps = props.eventBus
        var bus: EventBus.Configurable = BasicEventBus(enableCaching = eventProps.enableCaching)
        bus = if (props.auditLog.enabled) AuditingEventBus(requireAuditLoggerService(), bus) else bus
        bus = RecursiveEventBus(maxRecursionDepth = eventProps.maxRecursionDepth, bus)
        return if (eventProps.registerSubscriberBeans) BeanAwareEventBus(bus) else bus
    }

    /**
     * [CommandBus] structural implementation:
     * ```
     * BeanAwareCommandBus {
     *   AuditingCommandBus {
     *     TransactionalCommandBus {
     *       (startTransaction) ->
     *       EventPublishingCommandBus {
     *         AuthorizedCommandBus {
     *           (preAuthorize) ->
     *           BasicCommandBus {
     *             -> (handle) ->
     *           }
     *           -> (postAuthorize)
     *         }
     *         -> (publishEvents)
     *       }
     *       -> (endTransaction)
     *     }
     *     -> (auditLog errors)
     *   }
     * }
     * ```
     * Errors are audit-logged after the transaction of the command has been rolled back, so that the audit-log service
     * can persist them in a separate transaction (see [TransactionService.writeNew]). Domain events are audit-logged by
     * the [EventBus] within the transaction of the command.
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(value = [CommandBus::class], ignored = [CommandQueryBus::class])
    fun commandBus(eventBus: EventBus): CommandBus {
        val commandProps = props.commandBus
        // ((StartTransaction -> ((PreAuth -> (Handle) -> PostAuth) -> PublishEvents) -> EndTransaction) -> AuditLog)
        var bus: CommandBus.Configurable = BasicCommandBus(enableCaching = commandProps.enableCaching)
        bus = AuthorizedCommandBus(bus)
        bus = EventPublishingCommandBus(eventBus, bus)
        bus = if (commandProps.transactional) TransactionalCommandBus(requireTransactionService(), bus) else bus
        bus = if (props.auditLog.enabled) AuditingCommandBus(requireAuditLoggerService(), bus) else bus
        return if (commandProps.registerHandlerBeans) BeanAwareCommandBus(bus) else bus
    }

    /**
     * [QueryBus] structural implementation:
     * ```
     * BeanAwareQueryBus {
     *   TransactionalQueryBus {
     *     (startTransaction) ->
     *     AuthorizedQueryBus {
     *       (preAuthorize) ->
     *       BasicQueryBus {
     *         -> (handle) ->
     *       }
     *       -> (postAuthorize)
     *     }
     *     -> (endTransaction)
     *   }
     * }
     * ```
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(value = [QueryBus::class], ignored = [CommandQueryBus::class])
    fun queryBus(): QueryBus {
        val queryProps = props.queryBus
        // (StartTransaction -> (PreAuth -> (Handle) -> PostAuth) -> EndTransaction)
        var bus: QueryBus.Configurable = BasicQueryBus(enableCaching = queryProps.enableCaching)
        bus = AuthorizedQueryBus(bus)
        bus = if (queryProps.transactional) TransactionalQueryBus(requireTransactionService(), bus) else bus
        return if (queryProps.registerHandlerBeans) BeanAwareQueryBus(bus) else bus
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(QueryBus::class, CommandBus::class)
    fun commandQueryBus(queryBus: QueryBus, commandBus: CommandBus): CommandQueryBus {
        return CommandQueryBus(queryBus = queryBus, commandBus = commandBus)
    }

    private fun requireTransactionService(): TransactionService {
        return transactionServiceProvider.ifAvailable ?: TransactionService.DoNothing
    }

    private fun requireAuditLoggerService(): AuditLoggerService {
        if (!props.auditLog.enabled) {
            return AuditLoggerService.DoNothing
        }

        return AuditLoggerService.Delegate(
            buildList {
                if (props.auditLog.console) {
                    val objectMapper = objectMapperProvider.getObject()
                    add(AuditLoggerService.Console { objectMapper.writeValueAsString(it) })
                }
                addAll(auditLoggerServiceProvider.toList())
            },
        )
    }
}
