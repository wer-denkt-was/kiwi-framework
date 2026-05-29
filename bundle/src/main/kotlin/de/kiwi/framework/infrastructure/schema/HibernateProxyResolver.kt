package de.kiwi.framework.infrastructure.schema

import de.kiwi.api.schema.ProxyResolver
import org.hibernate.Hibernate

/**
 * [ProxyResolver] using [Hibernate], registered as a service in
 * `resources/META-INF/services/de.kiwi.api.schema.ProxyResolver`
 */
class HibernateProxyResolver : ProxyResolver {
    override fun <T : Any> resolve(proxy: T): T {
        @Suppress("UNCHECKED_CAST")
        return Hibernate.unproxy(proxy) as T
    }
}
