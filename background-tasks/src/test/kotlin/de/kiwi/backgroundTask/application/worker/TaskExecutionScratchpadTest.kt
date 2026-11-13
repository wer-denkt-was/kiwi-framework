package de.kiwi.backgroundTask.application.worker

import de.kiwi.api.backgroundTask.BackgroundTaskStages
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.module.kotlin.jacksonObjectMapper

class TaskExecutionScratchpadTest {

    /** Persistent store in memory, counting loads */
    private class MemoryStore(initial: Map<String, String?> = emptyMap()) : TaskExecutionScratchpad.PersistentStore {
        val values = initial.toMutableMap()
        var loads = 0

        override fun load(): Map<String, String?> {
            loads++
            return values.toMap()
        }

        override fun put(key: String, valueJson: String?) {
            values[key] = valueJson
        }

        override fun remove(key: String) {
            values.remove(key)
        }
    }

    @Test
    fun valuesAreLoadedOnceAndWrittenThrough() {
        val store = MemoryStore(mapOf("loaded" to "1", "nothing" to null))
        val scratchpad = TaskExecutionScratchpad(jacksonObjectMapper(), store)

        assertEquals(1, scratchpad.get<Int>("loaded"))
        assertTrue("nothing" in scratchpad)
        assertNull(scratchpad.get<String>("nothing"))

        scratchpad.put("written", listOf("a", "b"))
        assertEquals("""["a","b"]""", store.values["written"])
        assertEquals(listOf("a", "b"), scratchpad.get<List<String>>("written"))

        scratchpad.remove("loaded")
        assertFalse("loaded" in store.values)
        assertEquals(setOf("nothing", "written"), scratchpad.keys)
        assertEquals(1, store.loads)
    }

    @Test
    fun stageCheckpointKeysAreWritten() {
        val store = MemoryStore()
        val scratchpad = TaskExecutionScratchpad(jacksonObjectMapper(), store)
        val stageKey = BackgroundTaskStages.stageResultKey("test")

        scratchpad.put(stageKey, "result")
        assertEquals("result", scratchpad.get<String>(stageKey))
        assertEquals("\"result\"", store.values[stageKey])

        scratchpad.remove(stageKey)
        assertFalse(stageKey in scratchpad)
    }

    @Test
    fun invalidKeysAreRejected() {
        val scratchpad = TaskExecutionScratchpad(jacksonObjectMapper(), MemoryStore())
        assertThrows<IllegalArgumentException> { scratchpad.put(" ", 1) }
        assertThrows<IllegalArgumentException> { scratchpad.remove(" ") }
    }
}
