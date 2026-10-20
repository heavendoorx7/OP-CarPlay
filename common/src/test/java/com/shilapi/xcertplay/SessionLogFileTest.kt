package com.shilapi.xcertplay

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionLogFileTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun openingAnotherUiRetainsOnePreviousTimelineAndBoundsHistory() {
        val path = File(folder.root, "xcertplay.log")
        SessionLogFile(path).use { it.reset("first UI"); it.append("resize evidence") }
        SessionLogFile(path).use { it.reset("second UI"); it.append("restored session") }
        val previous = File(folder.root, "xcertplay.log.previous")
        assertTrue(previous.readText().contains("resize evidence"))
        assertFalse(path.readText().contains("resize evidence"))
        SessionLogFile(path).use { it.reset("third UI") }
        assertTrue(previous.readText().contains("restored session"))
        assertFalse(previous.readText().contains("first UI"))
        assertEquals(2, folder.root.listFiles()!!.size)
    }
}
