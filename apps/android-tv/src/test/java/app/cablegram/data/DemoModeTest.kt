package app.cablegram.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoModeTest {
    @Test
    fun `demo catalog is local https samples without legacy ids`() {
        val videos = demoLibraryVideos()
        assertEquals(7, videos.size)
        assertTrue(videos.all { isDemoVideoId(it.id) })
        assertTrue(videos.all { demoPlaybackUrl(it.id)!!.startsWith("https://") })
        assertTrue(videos.all { it.posterUrl!!.startsWith("https://") })
        assertTrue(videos.none { it.id.contains("t.me") })
        assertTrue(videos.none { demoPlaybackUrl(it.id)!!.contains("commondatastorage.googleapis.com") })
    }
}
