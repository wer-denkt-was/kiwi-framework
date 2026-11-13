package de.kiwi.backgroundTask

import de.kiwi.api.application.service.BackgroundTaskService
import de.kiwi.backgroundTask.application.messaging.BackgroundTaskMessage.StatusChanged
import de.kiwi.api.backgroundTask.BackgroundTaskStatus
import de.kiwi.backgroundTask.application.BackgroundTaskWorkerLifecycle
import de.kiwi.backgroundTask.application.BackgroundTaskWorkerShutdown
import de.kiwi.backgroundTask.infrastructure.TestBackgroundTaskMessaging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import java.time.Duration

/**
 * Graceful shutdown of the workers (with the local queue):
 * Running tasks may finish within the shutdown timeout, tasks still running afterward are aborted and marked as failed.
 */
@SpringBootTest(
    classes = [BackgroundTaskTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.datasource.url=jdbc:tc:mariadb:12.3:///background_tasks",
        "spring.liquibase.change-log=classpath:db/changelog/db.background-tasks-master.xml",
        "spring.jpa.hibernate.ddl-auto=validate",
        "kiwi.background-task.queue.provider=local",
        "kiwi.background-task.worker.enabled=true",
        "kiwi.background-task.worker.instances[0].include-categories=default",
        "kiwi.background-task.worker.instances[1].include-categories=default",
        "kiwi.background-task.worker.shutdown-timeout=2s",
    ],
)
@DirtiesContext
class BackgroundTaskWorkerShutdownIntegrationTest {
    private val testUser = TestUserDescriptor("Test User")
    private val timeout = Duration.ofSeconds(5)

    @Autowired
    lateinit var backgroundTaskService: BackgroundTaskService

    @Autowired
    lateinit var backgroundTaskMessaging: TestBackgroundTaskMessaging

    @Autowired
    lateinit var workerLifecycle: BackgroundTaskWorkerLifecycle

    @Autowired
    lateinit var taskInvocations: TaskInvocations

    @Test
    fun runningTasksFinishOrAreAbortedOnShutdown() {
        val finishing = backgroundTaskService.launch(ExampleTaskRequest("Finishing", delayMs = 500), testUser)
        val aborted = backgroundTaskService.launch(ExampleTaskRequest("Aborted", delayMs = 60_000), testUser)
        backgroundTaskMessaging.awaitMessage<StatusChanged.Started>(timeout, finishing.taskHandle).join()
        backgroundTaskMessaging.awaitMessage<StatusChanged.Started>(timeout, aborted.taskHandle).join()

        workerLifecycle.stop()
        assertFalse(workerLifecycle.isRunning)

        backgroundTaskMessaging.awaitMessage<StatusChanged.Completed>(timeout, finishing.taskHandle).join()
        val failed = backgroundTaskMessaging.awaitMessage<StatusChanged.Failed>(timeout, aborted.taskHandle).join()
        assertEquals(BackgroundTaskWorkerShutdown::class.java.name, failed.error.errorType)
        assertEquals(listOf("handle", "onFailure"), taskInvocations.methodsFor(aborted.taskHandle))

        assertEquals(BackgroundTaskStatus.Completed, backgroundTaskService.lookupDetails(finishing.taskHandle)?.status)
        assertEquals(BackgroundTaskStatus.Failed, backgroundTaskService.lookupDetails(aborted.taskHandle)?.status)
    }
}
