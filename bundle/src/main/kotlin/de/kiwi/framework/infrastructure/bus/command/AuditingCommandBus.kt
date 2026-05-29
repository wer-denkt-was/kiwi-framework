package de.kiwi.framework.infrastructure.bus.command

import de.kiwi.api.application.command.CommandBus
import de.kiwi.api.application.service.AuditLoggerService
import de.kiwi.api.domain.Command
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.DomainException
import de.kiwi.api.domain.UserDescriptor

class AuditingCommandBus(
    private val auditLoggerService: AuditLoggerService,
    private val delegate: CommandBus.Configurable,
) : CommandBus.Configurable by delegate {

    override fun <C : Command<R>, R> submit(command: C, user: UserDescriptor): DomainResult<R> {
        try {
            return delegate.submit(command, user)
        } catch (ex: DomainException) {
            auditLoggerService.auditLog(ex, user)
            throw ex
        }
    }
}
