package de.kiwi.api.application.command

import de.kiwi.api.domain.Command
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.UserDescriptor

/**
 * Bus aggregating [CommandHandler]s to resolve [Command] submissions.
 * This is a central interface in the core CQRS ("Command-Query Responsibility Segregation") system.
 */
interface CommandBus {
    /**
     * Submit a `Command` of type [C], returning a collection of the resulting [DomainResult].
     */
    fun <C : Command<R>, R> submit(command: C, user: UserDescriptor): DomainResult<R>

    /**
     * Mutable [CommandBus] that can be configured on startup
     */
    interface Configurable : CommandBus {
        /**
         * Register a new [CommandHandler] in the bus to handle [Command] submissions.
         */
        fun register(handler: CommandHandler<*, *>)
    }
}
