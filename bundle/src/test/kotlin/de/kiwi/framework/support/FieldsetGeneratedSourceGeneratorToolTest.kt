package de.kiwi.framework.support

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.jdbc.EmbeddedDatabaseConnection
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.util.FileSystemUtils
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isReadable
import kotlin.io.path.readText

/**
 * Create PersistenceContext to initialize JPA metamodel
 */
@EntityScan("de.kiwi.framework")
@DataJpaTest(properties = ["spring.jpa.hibernate.ddl-auto=none"])
@AutoConfigureTestDatabase(connection = EmbeddedDatabaseConnection.H2)
class FieldsetGeneratedSourceGeneratorToolTest {
    @Autowired(required = false)
    private var buildInfo: BuildProperties? = null

    @Test
    fun generateSources() {
        val classLoader = javaClass.classLoader
        val resourceLoader = DefaultResourceLoader(classLoader)
        withTempDirectory { dir ->
            val outputFile = FieldsetSourceGeneratorTool
                .scanPackages(resourceLoader, "de.kiwi.framework")
                .generateSources("TestFieldsets", dir, TypescriptFieldsetSourceRenderer())

            assertTrue(outputFile.exists() && outputFile.isReadable())
            println(outputFile.readText(Charsets.UTF_8))
        }
    }

    @Test
    fun generateBuildArtifacts() {
        val classLoader = javaClass.classLoader
        val resourceLoader = DefaultResourceLoader(classLoader)
        val artifactFile = FieldsetSourceGeneratorTool
            .scanPackages(resourceLoader, "de.kiwi.framework")
            .withBuildInfo(buildInfo)
            .generateBuildArtifacts("TestFieldsets", TypescriptFieldsetSourceRenderer())

        assertTrue(artifactFile.exists() && artifactFile.isReadable())
        println(artifactFile.readText(Charsets.UTF_8))
    }
}

private fun withTempDirectory(block: (Path) -> Unit) {
    val tmp = Files.createTempDirectory("test-fieldset-sources-")
    try {
        block(tmp)
    } finally {
        FileSystemUtils.deleteRecursively(tmp)
    }
}
