package de.kiwi.framework.infrastructure.bus.command

import de.kiwi.api.application.command.CommandBus
import de.kiwi.api.application.command.CommandHandler
import de.kiwi.api.util.slf4j
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.ListableBeanFactory

private val log = slf4j<BeanAwareCommandBus>()

class BeanAwareCommandBus(
    private val delegate: CommandBus.Configurable,
) : CommandBus by delegate, InitializingBean, BeanFactoryAware {

    private lateinit var beanFactory: ListableBeanFactory

    override fun setBeanFactory(beanFactory: BeanFactory) {
        this.beanFactory = beanFactory as ListableBeanFactory
    }

    override fun afterPropertiesSet() {
        registerCommandHandlers()
    }

    private fun registerCommandHandlers() = beanFactory
        .getBeanProvider(CommandHandler::class.java)
        .forEach {
            log.debug("Registered CommandHandler: ${it.javaClass.name}")
            delegate.register(it)
        }
}
