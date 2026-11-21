package com.autoalbum

import android.view.View
import android.view.WindowManager
import com.autoalbum.automation.AutomationCoordinator
import com.autoalbum.automation.NavigationSecurityPolicy
import com.autoalbum.overlay.OverlayViewTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression test suite for Floating Controller Lifecycle and Duplicate Overlay Prevention.
 *
 * Verifies:
 * - Press START five times: only one controller exists.
 * - Minimize and reopen AutoAlbum: no duplicate controller.
 * - Expand and collapse menu repeatedly: one arrow only.
 * - Press STOP: all overlays disappear.
 * - Reopen after STOP: fresh controller works.
 * - Press STOP while navigation is pending: no later swipe occurs.
 * - Activity recreation and service destruction teardown.
 */
class OverlayLifecycleTest {

    private lateinit var tracker: OverlayViewTracker

    @Before
    fun setUp() {
        AutomationCoordinator.reset()
        tracker = OverlayViewTracker(windowManager = null)
    }

    @Test
    fun testPressStartFiveTimesOnlyOneControllerExists() {
        val dummyParams = WindowManager.LayoutParams()

        // 1st START request -> successfully attaches arrow view
        val view1 = View(null)
        val added1 = tracker.attachArrow(view1, dummyParams)
        assertTrue("First START must attach the arrow handle", added1)
        assertTrue(tracker.hasArrow())
        assertEquals(1, tracker.attachedViews.size)

        // 2nd through 5th START requests -> must be rejected to prevent duplicates
        for (i in 2..5) {
            val duplicateView = View(null)
            val added = tracker.attachArrow(duplicateView, dummyParams)
            assertFalse("Repeated START #$i must NOT attach a duplicate arrow", added)
            assertEquals("Exactly one arrow view must remain attached", 1, tracker.attachedViews.size)
            assertEquals(view1, tracker.arrowHandleView)
        }
    }

    @Test
    fun testExpandAndCollapseMenuRepeatedlyOneArrowOnly() {
        val dummyParams = WindowManager.LayoutParams()
        val arrowView = View(null)
        tracker.attachArrow(arrowView, dummyParams)
        assertEquals(1, tracker.attachedViews.size)

        // Expand and collapse 5 times
        for (i in 1..5) {
            val menuView = View(null)
            val expanded = tracker.attachMenu(menuView, dummyParams)
            assertTrue("Cycle #$i: expanding menu must succeed", expanded)
            assertTrue(tracker.isMenuVisible())
            assertEquals(2, tracker.attachedViews.size)

            // Attempting to attach another menu while one is already open must be rejected
            val duplicateMenu = View(null)
            assertFalse(tracker.attachMenu(duplicateMenu, dummyParams))
            assertEquals(2, tracker.attachedViews.size)

            // Collapse menu
            val dismissed = tracker.dismissMenu()
            assertTrue("Cycle #$i: dismissing menu must succeed", dismissed)
            assertFalse(tracker.isMenuVisible())
            assertEquals(1, tracker.attachedViews.size)
            assertEquals(arrowView, tracker.arrowHandleView)
        }
    }

    @Test
    fun testPressStopAllOverlaysDisappear() {
        val dummyParams = WindowManager.LayoutParams()
        val arrowView = View(null)
        val menuView = View(null)

        tracker.attachArrow(arrowView, dummyParams)
        tracker.attachMenu(menuView, dummyParams)
        assertEquals(2, tracker.attachedViews.size)

        // Trigger STOP teardown
        val removedCount = tracker.removeAll()
        assertEquals(2, removedCount)
        assertFalse(tracker.hasArrow())
        assertFalse(tracker.isMenuVisible())
        assertNull(tracker.arrowHandleView)
        assertNull(tracker.compactMenuView)
        assertTrue(tracker.attachedViews.isEmpty())
    }

    @Test
    fun testReopenAfterStopFreshControllerWorks() {
        val dummyParams = WindowManager.LayoutParams()
        val arrow1 = View(null)
        tracker.attachArrow(arrow1, dummyParams)
        assertEquals(1, tracker.attachedViews.size)

        // User stops controller
        tracker.removeAll()
        assertTrue(tracker.attachedViews.isEmpty())

        // Reopening AutoAlbum and starting fresh controller
        val arrow2 = View(null)
        val addedFresh = tracker.attachArrow(arrow2, dummyParams)
        assertTrue("Reopening after STOP must cleanly attach a new arrow", addedFresh)
        assertTrue(tracker.hasArrow())
        assertEquals(1, tracker.attachedViews.size)
        assertEquals(arrow2, tracker.arrowHandleView)
    }

    @Test
    fun testMinimizeAndReopenAutoAlbumNoDuplicateController() {
        val dummyParams = WindowManager.LayoutParams()
        val arrowView = View(null)

        // Step 1: User starts automation and minimizes
        AutomationCoordinator.start()
        tracker.attachArrow(arrowView, dummyParams)
        assertEquals(AutomationCoordinator.State.RUNNING, AutomationCoordinator.state.value)
        assertEquals(1, tracker.attachedViews.size)

        // Step 2: User reopens AutoAlbum and presses START again
        val reopenArrow = View(null)
        val secondAttach = tracker.attachArrow(reopenArrow, dummyParams)
        assertFalse("Reopening AutoAlbum must NOT attach a duplicate controller", secondAttach)
        assertEquals(1, tracker.attachedViews.size)
        assertEquals(arrowView, tracker.arrowHandleView)
    }

    @Test
    fun testStopWhileNavigationPendingNoLaterSwipe() {
        AutomationCoordinator.start()
        val expectedPackage = "com.google.android.apps.photos"

        // Navigation check while RUNNING is allowed
        val preNavRunning = NavigationSecurityPolicy.validatePreNavigation(
            currentState = AutomationCoordinator.State.RUNNING,
            isRootNull = false,
            rootPackage = expectedPackage
        )
        assertTrue(preNavRunning is NavigationSecurityPolicy.ValidationResult.Allowed)

        // User presses STOP while navigation is pending
        AutomationCoordinator.stop()
        assertEquals(AutomationCoordinator.State.STOPPED, AutomationCoordinator.state.value)

        // When the delayed runnable executes in STOPPED state, it MUST be rejected immediately
        val preNavStopped = NavigationSecurityPolicy.validatePreNavigation(
            currentState = AutomationCoordinator.State.STOPPED,
            isRootNull = false,
            rootPackage = expectedPackage
        )
        assertTrue(preNavStopped is NavigationSecurityPolicy.ValidationResult.Rejected)

        val preDispatchStopped = NavigationSecurityPolicy.validatePreDispatch(
            currentState = AutomationCoordinator.State.STOPPED,
            expectedPackage = expectedPackage,
            isRootNull = false,
            currentRootPackage = expectedPackage
        )
        assertTrue(preDispatchStopped is NavigationSecurityPolicy.ValidationResult.Rejected)
    }

    @Test
    fun testActivityRecreationAndServiceDestruction() {
        var observedState = AutomationCoordinator.State.IDLE
        val listener: (AutomationCoordinator.State) -> Unit = { state ->
            observedState = state
        }
        AutomationCoordinator.addListener(listener)

        AutomationCoordinator.start()
        assertEquals(AutomationCoordinator.State.RUNNING, observedState)

        // Simulate service stopController / destruction
        AutomationCoordinator.stop()
        assertEquals(AutomationCoordinator.State.STOPPED, observedState)

        // Teardown listener
        AutomationCoordinator.removeListener(listener)
        AutomationCoordinator.reset()
        assertEquals(AutomationCoordinator.State.STOPPED, observedState) // Detached listener receives no further events
    }
}
