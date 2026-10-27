package app.cablegram.discovery

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Phone only (spec 008, 004 FR-022 amendment). The TV has app.cablegram.NoDiscoveryTest instead, which proves none of this exists there.

/**
 * Pins what video discovery can do. Adding a call, a field or a way to make a permit needs review against the
 * privacy page and the promise "It never reads your chats without your permission".
 */
class DiscoverySurfaceTest {
    private fun methods(type: Class<*>) = type.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
        .map { it.name.substringBefore('-') }.toSet()

    @Test
    fun `discovery exposes only the four calls and their helpers`() {
        assertEquals(
            setOf("discoveryChats", "chatVideoCount", "videoPager", "forwardToLibrary", "thumbnail", "purgeChat"),
            methods(TelegramDiscovery::class.java),
        )
    }

    @Test
    fun `the transport is the only list of TDLib calls discovery makes`() {
        assertEquals(
            setOf("myUserId", "chatIds", "chat", "isBot", "messageCount", "search", "forward", "downloadThumbnail", "deleteFile", "optimizeStorage"),
            methods(DiscoveryTransport::class.java),
        )
    }

    @Test
    fun `the models carry no message text beyond a caption or file name`() {
        fun fields(c: Class<*>) = c.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(setOf("id", "kind", "title", "protectedContent"), fields(DiscoveryChat::class.java))
        assertEquals(
            setOf("chatId", "messageId", "uniqueId", "title", "durationSeconds", "sizeBytes", "date", "thumbnailFileId", "protectedContent"),
            fields(DiscoveryVideo::class.java),
        )
        assertEquals(setOf("id", "type", "userId", "title", "protectedContent"), fields(TransportChat::class.java))
    }

    @Test
    fun `a scan permit cannot be made from outside the package`() {
        assertTrue("no constructor is reachable except from ScanPermit.Issuer", ScanPermit::class.java.declaredConstructors.filter { !it.isSynthetic }.all { Modifier.isPrivate(it.modifiers) })
        assertTrue("not serializable", !java.io.Serializable::class.java.isAssignableFrom(ScanPermit::class.java))
    }
}
