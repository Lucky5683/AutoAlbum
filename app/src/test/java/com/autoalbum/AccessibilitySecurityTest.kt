package com.autoalbum

import com.autoalbum.automation.AutomationCoordinator
import com.autoalbum.automation.NavigationSecurityPolicy
import com.autoalbum.automation.gallery.GalleryRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Automated security tests for AutoAlbum Accessibility Service security policies.
 *
 * Verifies:
 * 1. Window switching during delayed navigation (e.g. WhatsApp, Settings) cancels pending navigation.
 * 2. STOP and PAUSE states immediately reject pending navigation and dispatch.
 * 3. Null root window is rejected immediately without gesture/click execution.
 * 4. Unsupported active package is rejected (never falls through to swipe).
 * 5. Package mismatch right before dispatch is rejected.
 * 6. Accessibility service interruption and screen lock teardown.
 */
class AccessibilitySecurityTest {

    @Before
    fun setUp() {
        AutomationCoordinator.reset()
    }

    @Test
    fun testWindowSwitchDuringDelayCancelsPendingNavigation() {
        AutomationCoordinator.start()
        assertEquals(AutomationCoordinator.State.RUNNING, AutomationCoordinator.state.value)

        // While RUNNING, events from supported gallery packages should NOT trigger cancellation
        assertFalse(
            NavigationSecurityPolicy.shouldCancelPendingOnEvent(
                "com.google.android.apps.photos",
                AutomationCoordinator.State.RUNNING
            )
        )
        assertFalse(
            NavigationSecurityPolicy.shouldCancelPendingOnEvent(
                "com.sec.android.gallery3d",
                AutomationCoordinator.State.RUNNING
            )
        )

        // Switching to non-gallery applications during delay MUST trigger cancellation:
        // WhatsApp
        assertTrue(
            NavigationSecurityPolicy.shouldCancelPendingOnEvent(
                "com.whatsapp",
                AutomationCoordinator.State.RUNNING
            )
        )

        // Settings
        assertTrue(
            NavigationSecurityPolicy.shouldCancelPendingOnEvent(
                "com.android.settings",
                AutomationCoordinator.State.RUNNING
            )
        )

        // Banking / Sensitive apps
        assertTrue(
            NavigationSecurityPolicy.shouldCancelPendingOnEvent(
                "com.chase.sig.android",
                AutomationCoordinator.State.RUNNING
            )
        )

        // System UI / Launcher / Lockscreen
        assertTrue(
            NavigationSecurityPolicy.shouldCancelPendingOnEvent(
                "com.google.android.apps.nexuslauncher",
                AutomationCoordinator.State.RUNNING
            )
        )
    }

    @Test
    fun testStopAndPauseDuringPendingNavigation() {
        // Automation is RUNNING initially
        AutomationCoordinator.start()

        // 1. User stops automation during pending navigation
        AutomationCoordinator.stop()
        assertEquals(AutomationCoordinator.State.STOPPED, AutomationCoordinator.state.value)

        // Any incoming event in STOPPED state triggers cancellation
        assertTrue(
            NavigationSecurityPolicy.shouldCancelPendingOnEvent(
                "com.google.android.apps.photos",
                AutomationCoordinator.State.STOPPED
            )
        )

        // Pre-navigation revalidation in STOPPED state must be REJECTED
        val preNavResult = NavigationSecurityPolicy.validatePreNavigation(
            currentState = AutomationCoordinator.State.STOPPED,
            isRootNull = false,
            rootPackage = "com.google.android.apps.photos"
        )
        assertTrue(preNavResult is NavigationSecurityPolicy.ValidationResult.Rejected)

        // Pre-dispatch revalidation in STOPPED state must be REJECTED
        val preDispatchResult = NavigationSecurityPolicy.validatePreDispatch(
            currentState = AutomationCoordinator.State.STOPPED,
            expectedPackage = "com.google.android.apps.photos",
            isRootNull = false,
            currentRootPackage = "com.google.android.apps.photos"
        )
        assertTrue(preDispatchResult is NavigationSecurityPolicy.ValidationResult.Rejected)

        // 2. User pauses automation during pending navigation
        AutomationCoordinator.start()
        AutomationCoordinator.pause()
        assertEquals(AutomationCoordinator.State.PAUSED, AutomationCoordinator.state.value)

        val pausePreNav = NavigationSecurityPolicy.validatePreNavigation(
            currentState = AutomationCoordinator.State.PAUSED,
            isRootNull = false,
            rootPackage = "com.google.android.apps.photos"
        )
        assertTrue(pausePreNav is NavigationSecurityPolicy.ValidationResult.Rejected)
    }

    @Test
    fun testNullRootWindowRejection() {
        AutomationCoordinator.start()

        // Null root window at pre-navigation phase
        val nullNavResult = NavigationSecurityPolicy.validatePreNavigation(
            currentState = AutomationCoordinator.State.RUNNING,
            isRootNull = true,
            rootPackage = null
        )
        assertTrue(nullNavResult is NavigationSecurityPolicy.ValidationResult.Rejected)

        // Null root window immediately before dispatch
        val nullDispatchResult = NavigationSecurityPolicy.validatePreDispatch(
            currentState = AutomationCoordinator.State.RUNNING,
            expectedPackage = "com.google.android.apps.photos",
            isRootNull = true,
            currentRootPackage = null
        )
        assertTrue(nullDispatchResult is NavigationSecurityPolicy.ValidationResult.Rejected)
    }

    @Test
    fun testUnsupportedActivePackageRejection() {
        AutomationCoordinator.start()

        // When root window belongs to an unsupported app (e.g. WhatsApp, Banking, Browser)
        val unsupportedApps = listOf(
            "com.whatsapp",
            "com.android.settings",
            "com.google.android.chrome",
            "com.chase.sig.android",
            "org.telegram.messenger"
        )

        for (pkg in unsupportedApps) {
            val result = NavigationSecurityPolicy.validatePreNavigation(
                currentState = AutomationCoordinator.State.RUNNING,
                isRootNull = false,
                rootPackage = pkg
            )
            assertTrue(
                "Package '$pkg' must be rejected but was allowed",
                result is NavigationSecurityPolicy.ValidationResult.Rejected
            )
        }
    }

    @Test
    fun testPackageMismatchRejectionBeforeDispatch() {
        AutomationCoordinator.start()

        // Scenario: Navigation was scheduled for Google Photos, but active window switched
        // right before ACTION_CLICK or dispatchGesture()
        val expected = "com.google.android.apps.photos"

        // 1. Switched to WhatsApp
        val mismatchWhatsApp = NavigationSecurityPolicy.validatePreDispatch(
            currentState = AutomationCoordinator.State.RUNNING,
            expectedPackage = expected,
            isRootNull = false,
            currentRootPackage = "com.whatsapp"
        )
        assertTrue(mismatchWhatsApp is NavigationSecurityPolicy.ValidationResult.Rejected)

        // 2. Switched to Settings
        val mismatchSettings = NavigationSecurityPolicy.validatePreDispatch(
            currentState = AutomationCoordinator.State.RUNNING,
            expectedPackage = expected,
            isRootNull = false,
            currentRootPackage = "com.android.settings"
        )
        assertTrue(mismatchSettings is NavigationSecurityPolicy.ValidationResult.Rejected)

        // 3. Switched to another gallery (e.g. Samsung Gallery while expecting Google Photos)
        val mismatchOtherGallery = NavigationSecurityPolicy.validatePreDispatch(
            currentState = AutomationCoordinator.State.RUNNING,
            expectedPackage = expected,
            isRootNull = false,
            currentRootPackage = "com.sec.android.gallery3d"
        )
        assertTrue(mismatchOtherGallery is NavigationSecurityPolicy.ValidationResult.Rejected)

        // 4. Matches expected gallery and in allowlist -> ALLOWED
        val matched = NavigationSecurityPolicy.validatePreDispatch(
            currentState = AutomationCoordinator.State.RUNNING,
            expectedPackage = expected,
            isRootNull = false,
            currentRootPackage = expected
        )
        assertTrue(matched is NavigationSecurityPolicy.ValidationResult.Allowed)
    }

    @Test
    fun testScreenLockAndTeardownCoordination() {
        var observedState = AutomationCoordinator.State.IDLE
        var cancelledCount = 0

        val listener: (AutomationCoordinator.State) -> Unit = { state ->
            observedState = state
            if (state != AutomationCoordinator.State.RUNNING) {
                cancelledCount++
            }
        }

        AutomationCoordinator.addListener(listener)
        AutomationCoordinator.start()
        assertEquals(AutomationCoordinator.State.RUNNING, observedState)

        // Screen lock event
        AutomationCoordinator.onScreenLocked()
        assertEquals(AutomationCoordinator.State.STOPPED, observedState)
        assertTrue("Cancellation should have been triggered upon screen lock", cancelledCount > 0)

        // Incoming call event
        AutomationCoordinator.start()
        AutomationCoordinator.onIncomingCall()
        assertEquals(AutomationCoordinator.State.PAUSED, observedState)

        // Verify clean listener removal
        AutomationCoordinator.removeListener(listener)
        val countBefore = cancelledCount
        AutomationCoordinator.stop()
        assertEquals("Detached listener should not receive updates", countBefore, cancelledCount)
    }
}
