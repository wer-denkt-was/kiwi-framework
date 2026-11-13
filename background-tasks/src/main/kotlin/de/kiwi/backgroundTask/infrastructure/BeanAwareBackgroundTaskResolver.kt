package de.kiwi.backgroundTask.infrastructure

import de.kiwi.api.backgroundTask.BackgroundTaskHandler
import de.kiwi.api.backgroundTask.BackgroundTaskRequest
import de.kiwi.backgroundTask.application.BackgroundTaskResolver
import de.kiwi.backgroundTask.application.MissingBackgroundTaskHandler
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.getBeanProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * [BackgroundTaskResolver] resolving the first [BackgroundTaskHandler] bean that [can handle][BackgroundTaskHandler.canHandle]
 * a request. The handler beans are collected once on initialization; if [enableCaching] is set, the resolved handler is
 * cached per request class.
 */
class BeanAwareBackgroundTaskResolver(
    private val enableCaching: Boolean,
) : BackgroundTaskResolver, BeanFactoryAware, InitializingBean {
    private lateinit var beanFactory: BeanFactory
    private lateinit var taskHandlers: List<BackgroundTaskHandler<*>>
    private val cache = ConcurrentHashMap<Class<*>, BackgroundTaskHandler<*>>()

    override fun setBeanFactory(beanFactory: BeanFactory) {
        this.beanFactory = beanFactory
    }

    override fun afterPropertiesSet() {
        val taskProvider = beanFactory.getBeanProvider<BackgroundTaskHandler<*>>()
        taskHandlers = taskProvider.toList()
        cache.clear()
    }

    override fun <R : BackgroundTaskRequest> resolve(request: R): BackgroundTaskHandler<R> {
        val handler = if (enableCaching) findHandlerCached(request) else findHandler(request)

        @Suppress("UNCHECKED_CAST")
        return handler as BackgroundTaskHandler<R>
    }

    private fun findHandlerCached(request: BackgroundTaskRequest): BackgroundTaskHandler<*> {
        return cache.computeIfAbsent(request.javaClass) { findHandler(request) }
    }

    private fun findHandler(request: BackgroundTaskRequest): BackgroundTaskHandler<*> {
        return taskHandlers.find { it.canHandle(request) }
            ?: throw MissingBackgroundTaskHandler(request.javaClass.name)
    }
}
