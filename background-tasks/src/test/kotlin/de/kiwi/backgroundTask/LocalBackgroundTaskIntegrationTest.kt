package de.kiwi.backgroundTask

import org.springframework.test.context.TestPropertySource

/** Integration tests with the local, in-process queue */
@TestPropertySource(properties = ["kiwi.background-task.queue.provider=local"])
class LocalBackgroundTaskIntegrationTest : AbstractBackgroundTaskIntegrationTest()
