package de.kiwi.api.application.service

import de.kiwi.api.application.command.CommandBus
import de.kiwi.api.application.query.QueryBus
import de.kiwi.api.domain.Command
import de.kiwi.api.domain.Query
import de.kiwi.api.domain.Transactional
import de.kiwi.api.domain.UserDescriptor

/**
 * Convenience class offering the resolve/submit functionality of both a [CommandBus] and a [QueryBus].
 */
class CommandQueryBus(
    private val queryBus: QueryBus,
    private val commandBus: CommandBus,
) : CommandBus by commandBus, QueryBus by queryBus {

    /**
     * Automatically dispatch a [Transactional] command or query.
     * - [Commands][Command] are submitted to [CommandBus], and the result *value* (without events) is returned.
     * - [Queries][Query] are resolved by [QueryBus], and the result is retuned.
     * - Other [Transactional] implementations will throw an [IllegalArgumentException].
     */
    fun <R> dispatch(interaction: Transactional<R>, user: UserDescriptor): R = when (interaction) {
        is Query<R> -> queryBus.resolve(interaction, user)
        is Command<R> -> commandBus.submit(interaction, user).value
        else -> throw IllegalArgumentException(interaction.javaClass.name)
    }
}
