package de.kiwi.framework.infrastructure.bus

import com.fasterxml.jackson.databind.ObjectMapper
import de.kiwi.api.application.command.CommandBus
import de.kiwi.api.application.event.EventBus
import de.kiwi.api.application.query.QueryBus
import de.kiwi.api.application.service.AuditLoggerService
import de.kiwi.api.application.service.CommandQueryBus
import de.kiwi.api.application.service.TransactionService
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
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(KiwiFrameworkProperties::class)
class CommandQueryEventBusConfiguration(
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
    fun eventBus(): EventBus {
        val eventProps = props.eventBus
        var bus: EventBus.Configurable = BasicEventBus(enableCaching = eventProps.enableCaching)
        bus = if (props.enableAuditLogging) AuditingEventBus(auditLoggerService(), bus) else bus
        bus = RecursiveEventBus(maxRecursionDepth = eventProps.maxRecursionDepth, bus)
        return if (eventProps.registerSubscriberBeans) BeanAwareEventBus(bus) else bus
    }

    /**
     * [CommandBus] structural implementation:
     * ```
     * BeanAwareCommandBus {
     *   TransactionalCommandBus {
     *     (startTransaction) ->
     *     AuditingCommandBus {
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
     *       -> (auditLog)
     *     }
     *     -> (endTransaction)
     *   }
     * }
     * ```
     */
    @Bean
    fun commandBus(eventBus: EventBus): CommandBus {
        val commandProps = props.commandBus
        // (StartTransaction -> (((PreAuth -> (Handle) -> PostAuth) -> PublishEvents) -> AuditLog) -> EndTransaction)
        var bus: CommandBus.Configurable = BasicCommandBus(enableCaching = commandProps.enableCaching)
        bus = AuthorizedCommandBus(bus)
        bus = EventPublishingCommandBus(eventBus, bus)
        bus = if (props.enableAuditLogging) AuditingCommandBus(auditLoggerService(), bus) else bus
        bus = if (commandProps.transactional) TransactionalCommandBus(transactionService(), bus) else bus
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
    fun queryBus(): QueryBus {
        val queryProps = props.queryBus
        // (StartTransaction -> (PreAuth -> (Handle) -> PostAuth) -> EndTransaction)
        var bus: QueryBus.Configurable = BasicQueryBus(enableCaching = queryProps.enableCaching)
        bus = AuthorizedQueryBus(bus)
        bus = if (queryProps.transactional) TransactionalQueryBus(transactionService(), bus) else bus
        return if (queryProps.registerHandlerBeans) BeanAwareQueryBus(bus) else bus
    }

    @Bean
    fun commandQueryBus(queryBus: QueryBus, commandBus: CommandBus): CommandQueryBus {
        return CommandQueryBus(queryBus = queryBus, commandBus = commandBus)
    }

    private fun transactionService(): TransactionService {
        return transactionServiceProvider.ifAvailable ?: TransactionService.DoNothing
    }

    private fun auditLoggerService(): AuditLoggerService {
        if (!props.enableAuditLogging) {
            return AuditLoggerService.DoNothing
        }

        return AuditLoggerService.Delegate(buildList {
            if (props.auditLogConsole) {
                val objectMapper = objectMapperProvider.getObject()
                add(AuditLoggerService.Console { objectMapper.writeValueAsString(it) })
            }
            addAll(auditLoggerServiceProvider.toList())
        })
    }
}
