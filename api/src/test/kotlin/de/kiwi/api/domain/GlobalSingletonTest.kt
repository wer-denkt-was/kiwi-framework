package de.kiwi.api.domain

import de.kiwi.api.application.GlobalSingletonBean
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.StaticListableBeanFactory

class GlobalSingletonTest {
    private fun interface Greeter {
        fun greet(): String
    }

    private object TestSingleton : GlobalSingleton<Greeter>() {
        override val singletonName = "TestSingleton"
    }

    @AfterEach
    fun cleanup() {
        TestSingleton.cleanupInstance()
    }

    @Test
    fun uninitializedInstanceThrows() {
        assertThrows(IllegalStateException::class.java) { TestSingleton.instance }
    }

    @Test
    fun cleanupOnlyClearsOwnInstance() {
        val first = Greeter { "first" }
        val second = Greeter { "second" }

        TestSingleton.provideInstance(first)
        TestSingleton.provideInstance(second)

        assertFalse(TestSingleton.cleanupInstance(first))
        assertSame(second, TestSingleton.instance)

        assertTrue(TestSingleton.cleanupInstance(second))
        assertThrows(IllegalStateException::class.java) { TestSingleton.instance }
    }

    @Test
    fun beanDestroyKeepsInstanceOfOtherBean() {
        val first = Greeter { "first" }
        val second = Greeter { "second" }
        val firstBean = GlobalSingletonBean(TestSingleton, providerOf(first))
        val secondBean = GlobalSingletonBean(TestSingleton, providerOf(second))

        // Simulates two application contexts, where the first context is closed after the second one started
        firstBean.afterSingletonsInstantiated()
        secondBean.afterSingletonsInstantiated()
        firstBean.destroy()

        assertSame(second, TestSingleton.instance)

        secondBean.destroy()
        assertThrows(IllegalStateException::class.java) { TestSingleton.instance }
    }

    private fun providerOf(greeter: Greeter): ObjectProvider<Greeter> =
        StaticListableBeanFactory(mapOf("greeter" to greeter)).getBeanProvider(Greeter::class.java)
}
