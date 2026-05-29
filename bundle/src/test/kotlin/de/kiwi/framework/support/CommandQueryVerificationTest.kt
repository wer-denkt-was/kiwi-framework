package de.kiwi.framework.support

import de.kiwi.api.application.command.CommandHandler
import de.kiwi.api.application.query.QueryHandler
import de.kiwi.api.domain.Command
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.Query
import de.kiwi.api.domain.UserDescriptor
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader

class CommandQueryVerificationTest {
    @Test
    fun assertCommandQueryIntegrity() {
        val resourceLoader = DefaultResourceLoader(javaClass.classLoader)
        val exception = assertThrows(CommandQueryVerification.VerificationError::class.java) {
            CommandQueryVerification.run(resourceLoader) {
                domain("de.kiwi.framework.support")
                application("de.kiwi.framework.support")
            }
        }

        println(exception.message)
        println(exception.handlerErrors)

        assertThat(
            exception.handlerErrors, containsInAnyOrder(
                allOf(
                    hasProperty("subjectType", equalTo(MissingQuery::class.java)),
                    hasProperty("subjectHandlers", emptyCollectionOf(Class::class.java))
                ),
                allOf(
                    hasProperty("subjectType", equalTo(MissingCommand::class.java)),
                    hasProperty("subjectHandlers", emptyCollectionOf(Class::class.java))
                ),
                allOf(
                    hasProperty("subjectType", equalTo(AmbiguousQuery::class.java)),
                    hasProperty(
                        "subjectHandlers",
                        containsInAnyOrder(
                            equalTo(AmbiguousQueryFirstHandler::class.java),
                            equalTo(AmbiguousQuerySecondHandler::class.java),
                        )
                    )
                ),
                allOf(
                    hasProperty("subjectType", equalTo(AmbiguousCommand::class.java)),
                    hasProperty(
                        "subjectHandlers",
                        containsInAnyOrder(
                            equalTo(AmbiguousCommandFirstHandler::class.java),
                            equalTo(AmbiguousCommandSecondHandler::class.java),
                        )
                    )
                ),
            )
        )
    }
}

class HandledQuery : Query<Any>()
class MissingQuery : Query<Any>()
class AmbiguousQuery : Query<Any>()

class HandledCommand : Command<Unit>()
class MissingCommand : Command<Unit>()
class AmbiguousCommand : Command<Unit>()

class HandledQueryHandler : QueryHandler<HandledQuery, Any> {
    override fun handle(query: HandledQuery, user: UserDescriptor): Any = TODO()
}

class AmbiguousQueryFirstHandler : QueryHandler<AmbiguousQuery, Any> {
    override fun handle(query: AmbiguousQuery, user: UserDescriptor): Any = TODO()
}

class AmbiguousQuerySecondHandler : QueryHandler<AmbiguousQuery, Any> {
    override fun handle(query: AmbiguousQuery, user: UserDescriptor): Any = TODO()
}

class HandledCommandHandler : CommandHandler<HandledCommand, Unit> {
    override fun handle(command: HandledCommand, user: UserDescriptor): DomainResult<Unit> = TODO()
}

class AmbiguousCommandFirstHandler : CommandHandler<AmbiguousCommand, Unit> {
    override fun handle(command: AmbiguousCommand, user: UserDescriptor): DomainResult<Unit> = TODO()
}

class AmbiguousCommandSecondHandler : CommandHandler<AmbiguousCommand, Unit> {
    override fun handle(command: AmbiguousCommand, user: UserDescriptor): DomainResult<Unit> = TODO()
}
