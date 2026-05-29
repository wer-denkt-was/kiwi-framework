package de.kiwi.framework.infrastructure.bus.command

import de.kiwi.api.application.command.CommandBus
import de.kiwi.api.application.command.CommandHandler
import de.kiwi.api.domain.Command
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.framework.infrastructure.bus.HandlerCache

class BasicCommandBus(
    private val enableCaching: Boolean,
) : CommandBus.Configurable {

    private val handlers = ArrayList<CommandHandler<*, *>>()
    private val cache = HandlerCache(::findHandler)

    val cacheHitRate: Double get() = cache.hitRate()

    override fun register(handler: CommandHandler<*, *>) {
        synchronized(handlers) { handlers.add(handler) }
    }

    override fun <C : Command<R>, R> submit(command: C, user: UserDescriptor): DomainResult<R> {
        val handler = if (enableCaching) cache.compute(command) else findHandler(command)
        @Suppress("UNCHECKED_CAST") val typedHandler = handler as CommandHandler<C, R>
        return typedHandler.handle(command, user)
    }

    private fun findHandler(command: Command<*>): CommandHandler<*, *> = handlers
        .find { it.canHandle(command) }
        ?: throw AssertionError("Missing CommandHandler for ${command.javaClass.name}")
}
