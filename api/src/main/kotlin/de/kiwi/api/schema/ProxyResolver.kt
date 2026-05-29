package de.kiwi.api.schema

import org.springframework.core.Ordered
import java.util.*

/**
 * Service interface for un-proxying possibly proxied persistent objects.
 * An instance of this should be provided as an SPI service using Hibernate or a similar persistence provider.
 */
interface ProxyResolver : Ordered {
    override fun getOrder() = 0
    fun <T : Any> resolve(proxy: T): T
}

object ProxyResolverService {
    private val serviceLoader = ServiceLoader.load(ProxyResolver::class.java)!!

    private val resolvers by lazy { serviceLoader.sortedBy { it.order }.toList() }

    fun <T : Any> resolve(proxy: T): T = resolvers.fold(proxy) { target, resolver -> resolver.resolve(target) }
}
