package de.kiwi.framework.infrastructure.bus.query

import de.kiwi.api.application.query.QueryBus
import de.kiwi.api.application.query.QueryHandler
import de.kiwi.api.util.slf4j
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.ListableBeanFactory

private val log = slf4j<BeanAwareQueryBus>()

class BeanAwareQueryBus(
    private val delegate: QueryBus.Configurable,
) : QueryBus by delegate, BeanFactoryAware, InitializingBean {
    private lateinit var beanFactory: ListableBeanFactory

    override fun setBeanFactory(beanFactory: BeanFactory) {
        this.beanFactory = beanFactory as ListableBeanFactory
    }

    override fun afterPropertiesSet() {
        registerQueryHandlers()
    }

    private fun registerQueryHandlers() = beanFactory
        .getBeanProvider(QueryHandler::class.java)
        .forEach {
            log.debug("Registered QueryHandler: ${it.javaClass.name}")
            delegate.register(it)
        }
}
