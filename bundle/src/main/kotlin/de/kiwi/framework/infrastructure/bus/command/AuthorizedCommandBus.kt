package de.kiwi.framework.infrastructure.bus.command

import de.kiwi.api.application.command.CommandBus
import de.kiwi.api.domain.Authorized
import de.kiwi.api.domain.Command
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.UserDescriptor

class AuthorizedCommandBus(private val delegate: CommandBus.Configurable) : CommandBus.Configurable by delegate {
    override fun <C : Command<R>, R> submit(command: C, user: UserDescriptor): DomainResult<R> {
        if (command is Authorized) command.preAuthorize(user)
        val result = delegate.submit(command, user)
        if (command is Authorized) result.value?.let { command.postAuthorize(it, user) }
        return result
    }
}
