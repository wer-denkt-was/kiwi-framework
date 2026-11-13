package de.kiwi.backgroundTask

import de.kiwi.api.application.event.EventBus
import de.kiwi.api.application.service.BackgroundTaskService
import de.kiwi.api.application.service.ClockProvider
import de.kiwi.api.application.service.TransactionService
import de.kiwi.api.domain.DomainEvent
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.backgroundTask.application.BackgroundTaskQueue
import de.kiwi.backgroundTask.application.BackgroundTaskWorker
import de.kiwi.backgroundTask.application.BackgroundTaskWorkerBean
import de.kiwi.backgroundTask.application.BackgroundTaskWorkerLifecycle
import de.kiwi.backgroundTask.application.messaging.BackgroundTaskMessaging
import de.kiwi.backgroundTask.infrastructure.LocalBackgroundTaskQueue
import de.kiwi.backgroundTask.infrastructure.TestBackgroundTaskMessaging
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

/**
 * Tests the autoconfiguration without the Kiwi framework, in a non-web application context
 * (which doesn't declare a `ConversionService` bean).
 */
class KiwiBackgroundTaskAutoConfigurationTest {

    /** Application declaring its own autoconfiguration package, required by Spring Data if the module is disabled */
    @Configuration(proxyBeanMethods = false)
    @AutoConfigurationPackage(basePackages = ["de.kiwi.backgroundTask"])
    class ApplicationPackages

    // Mocked out EventBus
    private object DummyEventBus : EventBus {
        override fun publish(events: Iterable<DomainEvent>, user: UserDescriptor) = emptyList<DomainEvent>()
    }

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceAutoConfiguration::class.java,
                HibernateJpaAutoConfiguration::class.java,
                DataJpaRepositoriesAutoConfiguration::class.java,
                TransactionAutoConfiguration::class.java,
                KiwiBackgroundTaskPackagesAutoConfiguration::class.java,
                KiwiBackgroundTaskAutoConfiguration::class.java,
            ),
        )
        .withBean(ClockProvider::class.java, { ClockProvider.SystemUTC })
        .withBean(TransactionService::class.java, { TransactionService.DoNothing })
        .withBean(ObjectMapper::class.java, ::testObjectMapper)
        .withBean(EventBus::class.java, { DummyEventBus })
        .withPropertyValues(
            "spring.datasource.url=jdbc:h2:mem:background-task-autoconfiguration-test",
            "spring.jpa.hibernate.ddl-auto=create-drop",
        )

    @Test
    fun localQueueWithDefaultWorker() {
        contextRunner
            .withBean(TestBackgroundTaskMessaging::class.java)
            .withPropertyValues("kiwi.background-task.queue.provider=local", "kiwi.background-task.worker.enabled=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(BackgroundTaskService::class.java)
                assertThat(context).hasSingleBean(LocalBackgroundTaskQueue::class.java)
                assertThat(context).hasSingleBean(BackgroundTaskWorkerLifecycle::class.java)
                assertThat(context.getBeansOfType(BackgroundTaskWorker::class.java)).containsOnlyKeys("backgroundTaskWorker-0")
            }
    }

    @Test
    fun configuredWorkerInstances() {
        contextRunner
            .withBean(TestBackgroundTaskMessaging::class.java)
            .withPropertyValues(
                "kiwi.background-task.queue.provider=local",
                "kiwi.background-task.worker.enabled=true",
                "kiwi.background-task.worker.instances[0].include-categories=default",
                "kiwi.background-task.worker.instances[1].thread-mode=fixed-thread-pool",
                "kiwi.background-task.worker.instances[1].max-concurrent-tasks=4",
                "kiwi.background-task.worker.instances[1].exclude-categories=default",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                val workers = context.getBeansOfType(BackgroundTaskWorker::class.java)
                assertThat(workers).containsOnlyKeys("backgroundTaskWorker-0", "backgroundTaskWorker-1")
                assertThat(workers.getValue("backgroundTaskWorker-0").acceptsCategory("default")).isTrue()
                assertThat(workers.getValue("backgroundTaskWorker-0").acceptsCategory("special")).isFalse()
                assertThat(workers.getValue("backgroundTaskWorker-1").acceptsCategory("default")).isFalse()
                assertThat(workers.getValue("backgroundTaskWorker-1").acceptsCategory("special")).isTrue()
                assertThat(workers.getValue("backgroundTaskWorker-1").maxConcurrentTasks).isEqualTo(4)
            }
    }

    @Test
    fun workersAreClosedOnShutdown() {
        // Store reference to worker bean outside of running context
        lateinit var workerBean: BackgroundTaskWorkerBean

        contextRunner
            .withBean(TestBackgroundTaskMessaging::class.java)
            .withPropertyValues("kiwi.background-task.queue.provider=local", "kiwi.background-task.worker.enabled=true")
            .run { context ->
                workerBean = context.getBean<BackgroundTaskWorkerBean>()
                assertThat(workerBean.acceptsCategory("default")).isTrue()
            }

        // Closed worker has shut down its executor, and doesn't accept tasks anymore
        assertThat(workerBean.acceptsCategory("default")).isFalse()
    }

    @Test
    fun missingQueueProviderFailsStartup() {
        contextRunner.withBean(TestBackgroundTaskMessaging::class.java).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause()
                .isInstanceOf(InvalidConfigurationPropertyValueException::class.java)
                .hasMessageContaining("kiwi.background-task.queue.provider")
        }
    }

    @Test
    fun ownQueueDoesNotRequireQueueProvider() {
        contextRunner
            .withBean(TestBackgroundTaskMessaging::class.java)
            .withBean(BackgroundTaskQueue::class.java, ::LocalBackgroundTaskQueue)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(BackgroundTaskQueue::class.java)
            }
    }

    @Test
    fun workersRequireMessagingProvider() {
        contextRunner
            .withPropertyValues(
                "kiwi.background-task.queue.provider=local",
                "kiwi.background-task.worker.enabled=true",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).rootCause()
                    .isInstanceOf(InvalidConfigurationPropertyValueException::class.java)
                    .hasMessageContaining("kiwi.background-task.messaging.provider")
            }
    }

    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun workersAreDisabledByDefault(output: CapturedOutput) {
        contextRunner
            .withPropertyValues("kiwi.background-task.queue.provider=local")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(BackgroundTaskService::class.java)
                assertThat(context).doesNotHaveBean(BackgroundTaskWorker::class.java)
                assertThat(context).doesNotHaveBean(BackgroundTaskMessaging::class.java)
                assertThat(context).doesNotHaveBean(BackgroundTaskWorkerLifecycle::class.java)
            }

        // The local queue can't dispatch tasks without workers of the same instance
        assertThat(output).contains("Local BackgroundTaskQueue without BackgroundTaskWorkers")
    }

    @Test
    fun disabledModule() {
        contextRunner
            .withUserConfiguration(ApplicationPackages::class.java)
            .withPropertyValues("kiwi.background-task.enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(BackgroundTaskService::class.java)
                assertThat(context).doesNotHaveBean(BackgroundTaskWorker::class.java)
            }
    }
}
