package app.cablegram

import app.cablegram.discovery.DiscoveryTransport
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the TV discovery surface (spec 004 FR-022 & 008 FR-001 amended 2026-11-23).
 * Discovery runs on the TV only in that TV's own Telegram session when the owner agrees.
 */
class DiscoverySurfaceTest {
    private val allowed = setOf(
        "myUserId", "chatIds", "chat", "isBot", "recent", "search",
        "forward", "downloadThumbnail", "deleteFile", "optimizeStorage",
    )

    @Test
    fun `the discovery transport exposes only the allowed calls`() {
        val actual = DiscoveryTransport::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .map { it.name.substringBefore('-') }
            .toSet()
        assertEquals(allowed, actual)
    }
}
