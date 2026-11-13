package de.kiwi.backgroundTask

import de.kiwi.api.application.event.EventBus
import de.kiwi.api.application.service.BackgroundTaskService
import de.kiwi.api.application.service.ClockProvider
import de.kiwi.api.application.service.TransactionService
import de.kiwi.api.util.slf4j
import de.kiwi.backgroundTask.KiwiBackgroundTaskProperties.TaskMessagingProvider
import de.kiwi.backgroundTask.KiwiBackgroundTaskProperties.TaskQueueProvider
import de.kiwi.backgroundTask.application.*
import de.kiwi.backgroundTask.application.messaging.BackgroundTaskMessaging
import de.kiwi.backgroundTask.infrastructure.BeanAwareBackgroundTaskResolver
import de.kiwi.backgroundTask.infrastructure.LocalBackgroundTaskQueue
import de.kiwi.backgroundTask.infrastructure.RabbitBackgroundTaskQueue
import de.kiwi.backgroundTask.infrastructure.SimpBackgroundTaskMessaging
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.getBean
import org.springframework.beans.factory.getBeanProvider
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.convert.ApplicationConversionService
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.ConversionService
import org.springframework.core.env.Environment
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.messaging.simp.SimpMessagingTemplate
import tools.jackson.databind.ObjectMapper
import java.lang.management.ManagementFactory

private val log = slf4j<KiwiBackgroundTaskAutoConfiguration>()

/**
 * Autoconfiguration of the Kiwi background task module (`kiwi.background-task.enabled`, default `true`):
 * - [BackgroundTaskService] for launching and canceling tasks
 * - [BackgroundTaskQueue] selected by `kiwi.background-task.queue.provider`
 *   (required, unless the application declares its own queue bean)
 * - [BackgroundTaskResolver] resolving the application's `BackgroundTaskHandler` beans
 * - If `kiwi.background-task.worker.enabled` (opt-in, default `false`): The [BackgroundTaskWorker] beans configured by
 *   `kiwi.background-task.worker.instances`, registered in the queue and shut down gracefully by the
 *   [BackgroundTaskWorkerLifecycle], and the [BackgroundTaskMessaging] selected by
 *   `kiwi.background-task.messaging.provider` (required, unless the application declares its own messaging bean)
 * - [BackgroundTaskManagement] for monitoring and resuming tasks, exposed by the `backgroundTasks` management endpoint
 *   (see `KiwiBackgroundTaskManagementAutoConfiguration`)
 *
 * All beans are `@ConditionalOnMissingBean`, so the application may replace them.
 */
@AutoConfiguration(
    afterName = ["de.kiwi.framework.KiwiFrameworkAutoConfiguration"],
    after = [HibernateJpaAutoConfiguration::class, DataJpaRepositoriesAutoConfiguration::class],
)
@ConditionalOnBooleanProperty("kiwi.background-task.enabled", matchIfMissing = true)
@EnableConfigurationProperties(KiwiBackgroundTaskProperties::class)
class KiwiBackgroundTaskAutoConfiguration(
    private val props: KiwiBackgroundTaskProperties,
) {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(BackgroundTaskRepository::class)
    @EnableJpaRepositories(basePackageClasses = [BackgroundTaskRepository::class])
    internal class BackgroundTaskRepositoryConfiguration

    /** [BackgroundTaskService] launching, canceling, looking up, and resuming tasks */
    @Bean
    @ConditionalOnMissingBean(BackgroundTaskService::class)
    fun backgroundTaskService(
        taskRepositoryProvider: ObjectProvider<BackgroundTaskRepository>,
        dataRepositoryProvider: ObjectProvider<BackgroundTaskDataRepository>,
        taskQueueProvider: ObjectProvider<BackgroundTaskQueue>,
        conversionsProvider: ObjectProvider<ConversionService>,
        transactionService: TransactionService,
        clockProvider: ClockProvider,
        objectMapper: ObjectMapper,
        eventBus: EventBus,
    ): BackgroundTaskServiceBean {
        log.info("Initializing Kiwi BackgroundTaskService")
        val taskRepository = requireNotNull(taskRepositoryProvider.ifUnique) {
            "Missing ${BackgroundTaskRepository::class.simpleName} for Kiwi BackgroundTaskService"
        }
        val dataRepository = requireNotNull(dataRepositoryProvider.ifUnique) {
            "Missing ${BackgroundTaskDataRepository::class.simpleName} for Kiwi BackgroundTaskService"
        }
        val taskQueue = requireNotNull(taskQueueProvider.ifUnique) {
            "Missing ${BackgroundTaskQueue::class.simpleName} for Kiwi BackgroundTaskService" +
                    " (the RabbitMQ queue provider requires 'spring-boot-starter-amqp' on the classpath)"
        }
        // Non-web applications may not declare a ConversionService bean
        val conversions = conversionsProvider.getIfUnique { ApplicationConversionService.getSharedInstance() }

        return BackgroundTaskServiceBean(
            taskRepository = taskRepository,
            dataRepository = dataRepository,
            taskQueue = taskQueue,
            conversions = conversions,
            transactions = transactionService,
            clockProvider = clockProvider,
            objectMapper = objectMapper,
            eventBus = eventBus,
        )
    }

    /** [BackgroundTaskManagement] for administrators, if the default [BackgroundTaskServiceBean] is used */
    @Bean
    @ConditionalOnMissingBean(BackgroundTaskManagement::class)
    @ConditionalOnBean(BackgroundTaskServiceBean::class)
    fun backgroundTaskManagement(
        taskService: BackgroundTaskService,
        taskRepository: BackgroundTaskRepository,
        dataRepository: BackgroundTaskDataRepository,
        transactionService: TransactionService,
        clockProvider: ClockProvider,
        objectMapper: ObjectMapper,
    ) = BackgroundTaskManagement(
        taskService = taskService,
        taskRepository = taskRepository,
        dataRepository = dataRepository,
        transactions = transactionService,
        clockProvider = clockProvider,
        objectMapper = objectMapper,
        stalledAfter = props.management.stalledAfter,
    )

    /** Local [BackgroundTaskQueue] (`kiwi.background-task.queue.provider=local`), dispatching to local workers */
    @Bean
    @ConditionalOnMissingBean(BackgroundTaskQueue::class)
    @ConditionalOnTaskQueueProvider(TaskQueueProvider.Local)
    fun localBackgroundTaskQueue(): LocalBackgroundTaskQueue {
        log.info("Initializing local Kiwi BackgroundTaskQueue (single-instance)")
        if (!props.worker.enabled) {
            log.warn(
                "Local BackgroundTaskQueue without BackgroundTaskWorkers:" +
                        " Launching tasks will fail, enable workers with '${KiwiBackgroundTaskProperties.WORKER_ENABLED}=true'",
            )
        }

        return LocalBackgroundTaskQueue()
    }

    /**
     * RabbitMQ [BackgroundTaskQueue] (`kiwi.background-task.queue.provider=rabbit-mq`), if Spring AMQP is on the
     * classpath (`spring-boot-starter-amqp`, an optional dependency), requires a `ConnectionFactory`
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["org.springframework.amqp.rabbit.connection.ConnectionFactory"])
    internal class RabbitBackgroundTaskQueueConfiguration(private val props: KiwiBackgroundTaskProperties) {

        @Bean
        @ConditionalOnMissingBean(BackgroundTaskQueue::class)
        @ConditionalOnTaskQueueProvider(TaskQueueProvider.RabbitMq)
        fun rabbitBackgroundTaskQueue(
            connectionFactoryProvider: ObjectProvider<ConnectionFactory>,
            objectMapper: ObjectMapper,
        ): RabbitBackgroundTaskQueue {
            log.info("Initializing RabbitMQ Kiwi BackgroundTaskQueue")
            val connectionFactory = requireNotNull(connectionFactoryProvider.ifUnique) {
                "Missing ${ConnectionFactory::class.simpleName} (RabbitMQ) for Kiwi BackgroundTaskQueue"
            }
            return RabbitBackgroundTaskQueue(
                connectionFactory = connectionFactory,
                categoryConfigs = props.categories,
                rabbitConfig = props.queue.rabbitMq,
                objectMapper = objectMapper,
            )
        }
    }

    /** [BackgroundTaskResolver] resolving the `BackgroundTaskHandler` beans of the application */
    @Bean
    @ConditionalOnMissingBean(BackgroundTaskResolver::class)
    fun backgroundTaskResolver(): BackgroundTaskResolver {
        return BeanAwareBackgroundTaskResolver(
            enableCaching = props.handlers.enableCaching,
        )
    }

    /** Beans of the task workers, if enabled (`kiwi.background-task.worker.enabled`) */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBooleanProperty(KiwiBackgroundTaskProperties.WORKER_ENABLED)
    internal class BackgroundTaskWorkerConfiguration(private val props: KiwiBackgroundTaskProperties) {

        companion object {
            /**
             * Registers a bean definition per configured worker instance
             * @see BackgroundTaskWorkerRegistrar
             */
            @Bean
            @JvmStatic
            fun backgroundTaskWorkerRegistrar(environment: Environment) = BackgroundTaskWorkerRegistrar(environment)
        }

        @Bean
        @ConditionalOnMissingBean(BackgroundTaskLifecycleManager::class)
        fun backgroundTaskLifecycleManager(
            taskRepository: BackgroundTaskRepository,
            dataRepository: BackgroundTaskDataRepository,
            transactionService: TransactionService,
            clockProvider: ClockProvider,
            objectMapper: ObjectMapper,
            eventBus: EventBus,
        ) = BackgroundTaskLifecycleManager(
            taskRepository = taskRepository,
            dataRepository = dataRepository,
            transactions = transactionService,
            clockProvider = clockProvider,
            objectMapper = objectMapper,
            eventBus = eventBus,
        )


        @Bean
        fun backgroundTaskWorkerLifecycle(
            taskQueueProvider: ObjectProvider<BackgroundTaskQueue>,
            taskWorkerProvider: ObjectProvider<BackgroundTaskWorker>,
        ) = BackgroundTaskWorkerLifecycle(
            taskQueueProvider = taskQueueProvider,
            taskWorkerProvider = taskWorkerProvider,
            shutdownTimeout = props.worker.shutdownTimeout,
        )

        /**
         * Simp [BackgroundTaskMessaging] (`kiwi.background-task.messaging.provider=simp`), if Spring Messaging is on the
         * classpath (e.g. `spring-boot-starter-websocket`, an optional dependency), requires a `SimpMessagingTemplate`
         */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(name = ["org.springframework.messaging.simp.SimpMessagingTemplate"])
        internal class SimpBackgroundTaskMessagingConfiguration(private val props: KiwiBackgroundTaskProperties) {

            @Bean
            @ConditionalOnMissingBean(BackgroundTaskMessaging::class)
            @ConditionalOnTaskMessagingProvider(TaskMessagingProvider.Simp)
            fun simpBackgroundTaskMessaging(
                simpMessagingProvider: ObjectProvider<SimpMessagingTemplate>,
            ): SimpBackgroundTaskMessaging {
                log.info("Initializing SIMP Kiwi BackgroundTaskMessaging")
                val simpMessaging = requireNotNull(simpMessagingProvider.ifUnique) {
                    "Missing ${SimpMessagingTemplate::class.simpleName} for Kiwi BackgroundTaskMessaging"
                }
                return SimpBackgroundTaskMessaging(
                    messaging = simpMessaging,
                    topicPrefix = props.messaging.simp.messageTopic,
                )
            }
        }
    }
}

/**
 * Registers a [BackgroundTaskWorkerBean] definition per worker instance configured in
 * `kiwi.background-task.worker.instances` (or a single default worker), named `backgroundTaskWorker-<index>`.
 *
 * Bean definitions are registered instead of singleton instances, so the workers have a proper bean lifecycle
 * (initialized, and closed on shutdown), and their dependencies are resolved when they are instantiated, not while
 * post-processing the bean factory.
 */
internal class BackgroundTaskWorkerRegistrar(
    private val environment: Environment,
) : BeanDefinitionRegistryPostProcessor {

    override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
        val beanFactory = checkNotNull(registry as? ListableBeanFactory) {
            "Kiwi BackgroundTaskWorker registration requires a ListableBeanFactory"
        }

        val props = Binder
            .get(environment)
            .bindOrCreate("kiwi.background-task", KiwiBackgroundTaskProperties::class.java)

        val instances = props.worker.instances.ifEmpty {
            listOf(KiwiBackgroundTaskProperties.TaskWorker.Instance())
        }

        instances.forEachIndexed { index, workerConfig ->
            val beanName = "backgroundTaskWorker-$index"
            registry.registerBeanDefinition(
                beanName,
                RootBeanDefinition(BackgroundTaskWorkerBean::class.java) {
                    log.info("Initializing Kiwi BackgroundTaskWorker [$index]: $workerConfig")
                    createWorkerBeanInstance(beanFactory, beanName, props.worker, workerConfig)
                },
            )
        }
    }

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) = Unit

    private fun createWorkerBeanInstance(
        beanFactory: ListableBeanFactory,
        beanName: String,
        workersConfig: KiwiBackgroundTaskProperties.TaskWorker,
        instanceConfig: KiwiBackgroundTaskProperties.TaskWorker.Instance,
    ): BackgroundTaskWorkerBean {
        val taskMessagingProvider = beanFactory.getBeanProvider<BackgroundTaskMessaging>()
        val taskLifecycleProvider = beanFactory.getBeanProvider<BackgroundTaskLifecycleManager>()
        val taskResolverProvider = beanFactory.getBeanProvider<BackgroundTaskResolver>()

        val taskMessaging = requireNotNull(taskMessagingProvider.ifUnique) {
            "Missing ${BackgroundTaskMessaging::class.simpleName} for Kiwi BackgroundTaskWorker"
        }
        val taskLifecycle = requireNotNull(taskLifecycleProvider.ifUnique) {
            "Missing ${BackgroundTaskLifecycleManager::class.simpleName} for Kiwi BackgroundTaskWorker"
        }
        val taskResolver = requireNotNull(taskResolverProvider.ifUnique) {
            "Missing ${BackgroundTaskResolver::class.simpleName} for Kiwi BackgroundTaskWorker"
        }

        val runtimeMxBean = ManagementFactory.getRuntimeMXBean()
        val runtimeName = runtimeMxBean.name
        val workerName = "${runtimeName}/${beanName}"
        val workerConfig = BackgroundTaskWorkerBeanConfig(
            threadMode = instanceConfig.threadMode,
            maxConcurrentTasks = instanceConfig.maxConcurrentTasks,
            includeCategories = instanceConfig.includeCategories.toSet(),
            excludeCategories = instanceConfig.excludeCategories.toSet(),
            progressInterval = workersConfig.progressInterval,
            leaseDuration = workersConfig.leaseDuration,
            maxRetryDelay = workersConfig.maxRetryDelay,
        )

        return BackgroundTaskWorkerBean(
            workerName = workerName,
            workerConfig = workerConfig,
            taskResolver = taskResolver,
            taskLifecycle = taskLifecycle,
            taskMessaging = taskMessaging,
            objectMapper = beanFactory.getBean<ObjectMapper>(),
        )
    }
}
