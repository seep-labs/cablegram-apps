package app.cablegram.discovery

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Discovery never reads a whole chat history. A history also delivers service messages, and one of them (a changed
 * chat background) makes the TDLib wrapper throw on its receiver thread, which ends the app (found 2026-10-28 on a
 * scan with personal chats). It asks for videos and documents only, through a message-type filter.
 */
class DiscoveryNoHistoryTest {
    @Test
    fun `the discovery code never calls getChatHistory`() {
        val files = File("src/main/java/app/cablegram/discovery").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("run from the app module", files.size >= 8)
        val offenders = files.filter { "getChatHistory" in it.readText() }.map { it.name }
        assertTrue("Whole-history reads in: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the transport asks for videos and documents through a filter`() {
        val source = File("src/main/java/app/cablegram/discovery/TdlibDiscoveryTransport.kt").readText()
        assertTrue(source.contains("SearchMessagesFilterVideo") && source.contains("SearchMessagesFilterDocument"))
    }
}
