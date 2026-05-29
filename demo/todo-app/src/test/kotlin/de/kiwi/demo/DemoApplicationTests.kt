package de.kiwi.demo

import de.kiwi.api.util.slf4j
import de.kiwi.framework.support.CommandQueryVerification
import de.kiwi.framework.support.FieldsetSourceGeneratorTool
import de.kiwi.framework.support.TypescriptFieldsetSourceRenderer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.core.io.ResourceLoader
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

private val log = slf4j<DemoApplicationTests>()

@SpringBootTest(
    classes = [DemoApplication::class],
    properties = [
        "spring.profiles.active=application-tests",
        "spring.datasource.url=jdbc:tc:mysql:latest:///kiwi-demo",
        "kiwi.jwt.secret-key=00000000000000000000000000000000000000000000000000000000000000000000000000000000000000==",
    ]
)
@ContextConfiguration(classes = [DemoApplication::class])
@Testcontainers
class DemoApplicationTests {
    @Autowired
    lateinit var applicationContext: ApplicationContext

    @Autowired
    lateinit var resourceLoader: ResourceLoader

    companion object {
        @JvmStatic
        @Container
        val smtpContainer: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("schickling/mailcatcher:latest"))
                .waitingFor(Wait.forListeningPorts(1025))
                .withExposedPorts(1025)

        @JvmStatic
        @DynamicPropertySource
        fun smtpProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.mail.host", smtpContainer::getHost)
            registry.add("spring.mail.port", smtpContainer::getFirstMappedPort)
        }
    }

    @Test
    fun contextLoads() {
        log.info("Demo context loaded successfully")
    }

    @Test
    fun commandQueryIntegrity() {
        CommandQueryVerification.run(resourceLoader, applicationContext)
    }

    @Test
    fun generateFieldsetSources() {
        FieldsetSourceGeneratorTool
            .scanContext(resourceLoader, applicationContext)
            .generateBuildArtifacts(moduleName = "demo", renderer = TypescriptFieldsetSourceRenderer())
    }
}
