package de.kiwi.storage.infrastructure

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class FileUtilsTests {

    @Test
    fun withTemporaryFile() {
        var tmpFileOutsideScope: Path? = null
        val dataFromFile = withTemporaryFile { tmpFile ->
            tmpFileOutsideScope = tmpFile
            println("Temporary file: $tmpFile")
            assertTrue(Files.exists(tmpFile))

            Files.writeString(tmpFile, "Hello World")
            Files.readString(tmpFile)
        }

        assertFalse(Files.exists(tmpFileOutsideScope!!))
        assertEquals("Hello World", dataFromFile)
    }

    @Test
    fun slugifyFilename() {
        val original = "C:\\foo/bar-baz/12345/Thîs fîl€nämé cøŊŧain$ löt§ 0f ¢ſäp!.txt"
        val slug = original.slugifyFilename()

        println("[$original] --(slug)-> [$slug]")
        assertTrue(slug.matches("[a-z0-9_]*".toRegex()), "Slug contains invalid characters")
    }

    @Test
    fun checksumSha256() {
        // Verify against GNU sha256 "hashalot" util:
        // $ echo -n "This was a triumph. I'm making a note here; \"Huge success\"." | sha256 -x
        val text = """This was a triumph. I'm making a note here; "Huge success"."""
        val sha256 = withTemporaryFile { tmp ->
            tmp.writeText(text)
            tmp.fileChecksumSha256()
        }

        println("$text --(sha256)-> $sha256")
        assertEquals("63549e6b457e03eceb8bfcb04846bc76c7694bb1362b364a69b13d5b1df4dacf", sha256.formatChecksum())
    }
}
