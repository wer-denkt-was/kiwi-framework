package de.kiwi.framework.infrastructure.util

import de.kiwi.api.generic.GenericCreate
import de.kiwi.framework.infrastructure.generic.CreatePublisher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.DefaultResourceLoader

class ClassPathScannerUtilTest {
    private val scanner = ClassPathScannerUtil().apply {
        setResourceLoader(DefaultResourceLoader())
        setEnvironment(StandardEnvironment())
    }

    @Test
    fun findPublicClassesOnly() {
        val publicLocations = listOf("de.kiwi.framework.infrastructure.generic")
        val publicClasses = scanner.findClassesOfTypeInPackages<GenericCreate<*>>(publicLocations)
        assertTrue(CreatePublisher::class.java in publicClasses)

        // Private nested test commands, e.g. of GenericBodyCommandTest, are ignored
        val privateLocations = listOf("de.kiwi.testing.generic")
        val privateClasses = scanner.findClassesOfTypeInPackages<GenericCreate<*>>(privateLocations)
        assertEquals(emptyList<Class<*>>(), privateClasses)
    }
}
