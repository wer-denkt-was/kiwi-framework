package de.kiwi.framework.infrastructure.bus.query

import de.kiwi.api.application.query.QueryBus
import de.kiwi.api.application.query.QueryHandler
import de.kiwi.api.application.service.TransactionService
import de.kiwi.api.domain.Query
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.framework.testUserDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

class QueryBusTest {
    private lateinit var queryBus: QueryBus.Configurable
    private lateinit var basicBus: BasicQueryBus

    @BeforeEach
    fun setUp() {
        basicBus = BasicQueryBus(enableCaching = true)
        queryBus = TransactionalQueryBus(
            transactionService = TransactionService.DoNothing,
            delegate = AuthorizedQueryBus(
                delegate = basicBus
            ),
        )
        queryBus.register(IdentityQueryHandler<Any>())
        queryBus.register(GreetingQueryHandler())
    }

    @Test
    fun resolveIdentity() {
        val user = testUserDescriptor(username = "test")
        val intResult = queryBus.resolve(IdentityQuery(42), user)
        val stringResult = queryBus.resolve(IdentityQuery("Success!"), user)

        assertEquals(42, intResult)
        assertEquals("Success!", stringResult)
    }

    @Test
    fun resolveGreeting() {
        val user = testUserDescriptor(username = "test")
        val englishResult = queryBus.resolve(GreetingQuery(Locale.ENGLISH), user)
        val germanResult = queryBus.resolve(GreetingQuery(Locale.GERMAN), user)

        assertEquals("Hello World", englishResult)
        assertEquals("Guten Tag, Welt", germanResult)
    }

    @Test
    fun cacheHitRate() {
        val user = testUserDescriptor(username = "test")
        queryBus.resolve(IdentityQuery(1), user)
        queryBus.resolve(IdentityQuery(2), user)
        queryBus.resolve(IdentityQuery(3), user)
        queryBus.resolve(IdentityQuery(4), user)

        // 3/4 resolve lookups are cached
        println("Query cache hit rate = ${basicBus.cacheHitRate}")
        assertEquals(0.75, basicBus.cacheHitRate, 0.001)
    }
}

private class IdentityQuery<T>(val value: T) : Query<T>()

private class IdentityQueryHandler<T> : QueryHandler<IdentityQuery<T>, T> {
    override fun handle(query: IdentityQuery<T>, user: UserDescriptor): T {
        return query.value
    }
}

private class GreetingQuery(val locale: Locale) : Query<String>()

private class GreetingQueryHandler : QueryHandler<GreetingQuery, String> {
    override fun handle(query: GreetingQuery, user: UserDescriptor): String {
        return when (query.locale) {
            Locale.ENGLISH -> "Hello World"
            Locale.GERMAN -> "Guten Tag, Welt"
            else -> throw Exception(query.locale.displayName)
        }
    }
}
