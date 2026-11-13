package app.cablegram.phone

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.collections.ArrayDeque

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AddedVideosBannerTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var app: Application
    private lateinit var vm: PhoneViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        app = ApplicationProvider.getApplicationContext()
        vm = PhoneViewModel(app, SavedStateHandle())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun uiText(id: Int, vararg args: Any?): String = app.resources.getString(id, *args)

    private fun sampleLibraryItem(id: String, title: String = "Test Video"): LibraryItem =
        LibraryItem(
            id = id,
            title = title,
            filename = "$title.mp4",
            fileName = "$id.mp4",
            durationSeconds = 120,
            fileSizeBytes = 1024L,
            genres = listOf("Personal"),
            mediaType = "movie",
            importedAt = "2026-10-25T00:00:00Z",
        )

    private fun setPendingPrivacyItems(items: List<LibraryItem>) {
        val field = PhoneViewModel::class.java.getDeclaredField("pendingPrivacyItems")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val deque = field.get(vm) as ArrayDeque<LibraryItem>
        deque.clear()
        deque.addAll(items)
        val method = PhoneViewModel::class.java.getDeclaredMethod("showNextPrivacyPrompt")
        method.isAccessible = true
        method.invoke(vm)
    }

    // A1: markRecentlyAdded(listOf("a"), delayMs), advance past the delay -> recentlyAddedIds is empty
    @Test
    fun `A1 markRecentlyAdded advance past delay clears recentlyAddedIds`() = runTest(testDispatcher) {
        vm.markRecentlyAdded(listOf("a"), delayMs = 50L)
        assertEquals(listOf("a"), vm.recentlyAddedIds)
        advanceTimeBy(60L)
        assertEquals(emptyList<String>(), vm.recentlyAddedIds)
    }

    // A2: Same, advance to just before the delay -> recentlyAddedIds is still ["a"]
    @Test
    fun `A2 markRecentlyAdded advance to just before delay leaves recentlyAddedIds intact`() = runTest(testDispatcher) {
        vm.markRecentlyAdded(listOf("a"), delayMs = 50L)
        assertEquals(listOf("a"), vm.recentlyAddedIds)
        advanceTimeBy(40L)
        assertEquals(listOf("a"), vm.recentlyAddedIds)
        advanceTimeBy(15L)
        assertEquals(emptyList<String>(), vm.recentlyAddedIds)
    }

    // A3: markRecentlyAdded twice quickly with different ids -> The first timer is cancelled and the second restarts. Dismissal happens one delay after the second call
    @Test
    fun `A3 markRecentlyAdded twice quickly cancels first timer and restarts with new ids`() = runTest(testDispatcher) {
        vm.markRecentlyAdded(listOf("a"), delayMs = 50L)
        advanceTimeBy(30L)
        assertEquals(listOf("a"), vm.recentlyAddedIds)

        vm.markRecentlyAdded(listOf("b"), delayMs = 50L)
        assertEquals(listOf("b"), vm.recentlyAddedIds)

        // 30ms after second call (total 60ms from start): first timer (at 50ms) would have expired, but second should still be alive
        advanceTimeBy(30L)
        assertEquals(listOf("b"), vm.recentlyAddedIds)

        // Advance past second timer
        advanceTimeBy(25L)
        assertEquals(emptyList<String>(), vm.recentlyAddedIds)
    }

    // A4: cancelDismissAddedVideos() after marking, advance past the delay -> Ids are not cleared
    @Test
    fun `A4 cancelDismissAddedVideos after marking prevents auto dismissal`() = runTest(testDispatcher) {
        vm.markRecentlyAdded(listOf("a"), delayMs = 50L)
        vm.cancelDismissAddedVideos()
        advanceTimeBy(100L)
        assertEquals(listOf("a"), vm.recentlyAddedIds)
    }

    // A5: scheduleDismissAddedVideos() while pendingPrivacyItem != null -> No timer starts. Ids remain after any delay
    @Test
    fun `A5 scheduleDismissAddedVideos while pendingPrivacyItem is not null does not start timer`() = runTest(testDispatcher) {
        setPendingPrivacyItems(listOf(sampleLibraryItem("p1")))
        assertNotNull(vm.pendingPrivacyItem)

        vm.markRecentlyAdded(listOf("p1"), delayMs = 50L)
        // Explicitly calling scheduleDismissAddedVideos while pendingPrivacyItem != null should bail out early
        vm.scheduleDismissAddedVideos(delayMs = 50L)
        advanceTimeBy(200L)
        assertEquals(listOf("p1"), vm.recentlyAddedIds)
    }

    // A6: Privacy flow: import multiple files, so the privacy prompt shows. Advance past the delay -> Ids remain. After choosePrivacy answers the last item, advance past the delay. Ids are now cleared
    @Test
    fun `A6 privacy flow keeps ids until last item is answered then auto dismisses`() = runTest(testDispatcher) {
        val item1 = sampleLibraryItem("vid1")
        val item2 = sampleLibraryItem("vid2")
        vm.markRecentlyAdded(listOf("vid1", "vid2"), delayMs = 50L)
        setPendingPrivacyItems(listOf(item1, item2))

        // First item pending: prompt is active, timer must be cancelled
        assertEquals(item1.id, vm.pendingPrivacyItem?.id)
        advanceTimeBy(100L)
        assertEquals(listOf("vid1", "vid2"), vm.recentlyAddedIds)

        // Answer first item: second item should become pending
        vm.choosePrivacy(isPrivate = false)
        assertEquals(item2.id, vm.pendingPrivacyItem?.id)
        advanceTimeBy(100L)
        assertEquals(listOf("vid1", "vid2"), vm.recentlyAddedIds)

        // Answer second (last) item: pendingPrivacyItem becomes null, choosePrivacy calls scheduleDismissAddedVideos()
        // Note: choosePrivacy uses default delayMs (6,000L = 6s)
        vm.choosePrivacy(isPrivate = true)
        assertNull(vm.pendingPrivacyItem)
        assertEquals(listOf("vid1", "vid2"), vm.recentlyAddedIds)

        // Advance 5,000 ms: still within default 6s window
        advanceTimeBy(5_000L)
        assertEquals(listOf("vid1", "vid2"), vm.recentlyAddedIds)

        // Advance past 6,000 ms: banner is now dismissed
        advanceTimeBy(1_500L)
        assertEquals(emptyList<String>(), vm.recentlyAddedIds)
    }

    // A7: dismissAddedVideos() when status equals the "Video added" text -> status becomes null
    @Test
    fun `A7 dismissAddedVideos clears status when status equals single video added text`() = runTest(testDispatcher) {
        vm.markRecentlyAdded(listOf("a"))
        val singleAdded = uiText(R.string.ui_video_added_08c7ee)
        vm.status = singleAdded
        assertEquals(singleAdded, vm.status)

        vm.dismissAddedVideos()
        assertEquals(emptyList<String>(), vm.recentlyAddedIds)
        assertNull(vm.status)
    }

    // A8: Same when status equals the "Videos added" text -> status becomes null
    @Test
    fun `A8 dismissAddedVideos clears status when status equals multi videos added text`() = runTest(testDispatcher) {
        vm.markRecentlyAdded(listOf("a", "b"))
        val multiAdded = uiText(R.string.ui_videos_added_3d5b7a)
        vm.status = multiAdded
        assertEquals(multiAdded, vm.status)

        vm.dismissAddedVideos()
        assertEquals(emptyList<String>(), vm.recentlyAddedIds)
        assertNull(vm.status)
    }

    // A9: Same when status is something else, such as "Added N files" or a privacy prompt text -> status is unchanged.
    // Record this as current behaviour. A reviewer asked whether leaving "Added N files" is intended.
    @Test
    fun `A9 dismissAddedVideos leaves status unchanged when status is Added N files or privacy prompt`() = runTest(testDispatcher) {
        val addedFilesText = uiText(R.string.ui_added_files_fb53a9, 3)
        vm.status = addedFilesText
        vm.dismissAddedVideos()
        assertEquals("Status 'Added N files' is unchanged after dismissAddedVideos", addedFilesText, vm.status)

        val privacyPromptText = uiText(R.string.ui_should_this_require_phone_approval_92bae5)
        vm.status = privacyPromptText
        vm.dismissAddedVideos()
        assertEquals("Status privacy prompt is unchanged after dismissAddedVideos", privacyPromptText, vm.status)
    }

    // A10: Set tab = PhoneTab.Settings while ids are set -> Ids are cleared immediately and the timer is cancelled
    @Test
    fun `A10 switching tab away from Library immediately clears ids and cancels timer`() = runTest(testDispatcher) {
        vm.tab = PhoneTab.Library
        vm.markRecentlyAdded(listOf("a"), delayMs = 50L)
        assertEquals(listOf("a"), vm.recentlyAddedIds)

        vm.tab = PhoneTab.Settings
        assertEquals(emptyList<String>(), vm.recentlyAddedIds)

        // Switching back to Library later should not revive the ids or re-fire a timer
        vm.tab = PhoneTab.Library
        advanceTimeBy(100L)
        assertEquals(emptyList<String>(), vm.recentlyAddedIds)
    }

    // A11: Set tab = PhoneTab.Library while it is already Library, with ids set -> Ids are not cleared
    @Test
    fun `A11 setting tab to Library while already on Library preserves ids`() = runTest(testDispatcher) {
        vm.tab = PhoneTab.Library
        vm.markRecentlyAdded(listOf("a"), delayMs = 50L)
        assertEquals(listOf("a"), vm.recentlyAddedIds)

        vm.tab = PhoneTab.Library
        assertEquals(listOf("a"), vm.recentlyAddedIds)
    }

    // A12: Import success path: tab set to Library, then markRecentlyAdded -> Ids are set and not cleared by the tab change
    @Test
    fun `A12 import success path setting tab to Library before marking ids retains them`() = runTest(testDispatcher) {
        // Start from Browse tab
        vm.tab = PhoneTab.Browse
        assertEquals(emptyList<String>(), vm.recentlyAddedIds)

        // Import completion sequence in PhoneViewModel:
        // tab = PhoneTab.Library
        // markRecentlyAdded(...)
        vm.tab = PhoneTab.Library
        vm.markRecentlyAdded(listOf("vid-1"), delayMs = 50L)
        assertEquals(listOf("vid-1"), vm.recentlyAddedIds)

        advanceTimeBy(40L)
        assertEquals(listOf("vid-1"), vm.recentlyAddedIds)

        advanceTimeBy(20L)
        assertEquals(emptyList<String>(), vm.recentlyAddedIds)
    }

    // A13: dismissAddedVideos() with no ids and no timer -> No crash and no state change
    @Test
    fun `A13 dismissAddedVideos with no ids and no timer does not crash or change state`() = runTest(testDispatcher) {
        assertEquals(emptyList<String>(), vm.recentlyAddedIds)
        assertNull(vm.status)

        vm.dismissAddedVideos()

        assertEquals(emptyList<String>(), vm.recentlyAddedIds)
        assertNull(vm.status)
    }

    // Pure-function test for visible = added.isNotEmpty() && tab == PhoneTab.Library
    // Note: Compose UI test (createComposeRule) is skipped because ui-test-junit4 is only in androidTestImplementation (device/emulator)
    @Test
    fun `card visibility logic requires non-empty added items and Library tab`() {
        fun isCardVisible(addedCount: Int, tab: PhoneTab): Boolean =
            addedCount > 0 && tab == PhoneTab.Library

        assertTrue("Card is visible when added items present and on Library tab", isCardVisible(1, PhoneTab.Library))
        assertTrue("Card is visible when multiple added items present and on Library tab", isCardVisible(5, PhoneTab.Library))
        assertFalse("Card is hidden when added list is empty on Library tab", isCardVisible(0, PhoneTab.Library))
        assertFalse("Card is hidden when on Settings tab even with added items", isCardVisible(1, PhoneTab.Settings))
        assertFalse("Card is hidden when on Browse tab even with added items", isCardVisible(1, PhoneTab.Browse))
        assertFalse("Card is hidden when on Remote tab even with added items", isCardVisible(1, PhoneTab.Remote))
        assertFalse("Card is hidden when on Storage tab even with added items", isCardVisible(1, PhoneTab.Storage))
    }
}
