package de.kiwi.api.application.command

import de.kiwi.api.domain.Command
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.util.lookupSupertypeArgument

/**
 * Handler processing [Command]s of a given subtype [C].
 * Only a single handler must exist for any given command class.
 * @param C Command type handled by the implementing handler instance
 */
interface CommandHandler<C, R> where C : Command<R> {
    /**
     * Handle the execution of the given [Command] by the given [UserDescriptor]
     */
    fun handle(command: C, user: UserDescriptor): DomainResult<R>

    /**
     * Returns `true` if this CommandHandler is able to handle the given command instance,
     * by default if it is an instance of the type implemented by the handler class.
     */
    fun canHandle(command: Command<*>): Boolean {
        val handledType = javaClass.lookupSupertypeArgument<CommandHandler<*, *>>(0)
        return handledType.isInstance(command)
    }
}
