package de.kiwi.api.domain

/**
 * Abstract base class for commands submitted to a [CommandBus][de.kiwi.api.application.command.CommandBus],
 * handled by a [CommandHandler][de.kiwi.api.application.command.CommandHandler].
 * @param R Result type returned by executing this command
 */
abstract class Command<out R> : Transactional<R>
