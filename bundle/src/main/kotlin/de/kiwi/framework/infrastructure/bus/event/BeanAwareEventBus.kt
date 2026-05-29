package de.kiwi.framework.infrastructure.bus.event

import de.kiwi.api.application.event.EventBus
import de.kiwi.api.application.event.EventSubscriber
import de.kiwi.api.util.slf4j
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.ListableBeanFactory

private val log = slf4j<BeanAwareEventBus>()

class BeanAwareEventBus(
    private val delegate: EventBus.Configurable,
) : EventBus by delegate, BeanFactoryAware, InitializingBean {
    private lateinit var beanFactory: ListableBeanFactory

    override fun setBeanFactory(beanFactory: BeanFactory) {
        this.beanFactory = beanFactory as ListableBeanFactory
    }

    override fun afterPropertiesSet() {
        registerEventSubscribers()
    }

    private fun registerEventSubscribers() = beanFactory
        .getBeanProvider(EventSubscriber::class.java)
        .forEach {
            log.debug("Registered EventSubscriber: ${it.javaClass.name}")
            delegate.subscribe(it)
        }
}
