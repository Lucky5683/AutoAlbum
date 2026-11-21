package com.autoalbum

import com.autoalbum.automation.AutomationCoordinator
import com.autoalbum.automation.gallery.GalleryRegistry
import com.autoalbum.automation.gallery.GooglePhotosAdapter
import com.autoalbum.automation.gallery.SamsungGalleryAdapter
import com.autoalbum.automation.gallery.GenericGalleryAdapter
import com.autoalbum.overlay.FloatingMenuColorHelper
import com.autoalbum.overlay.MenuButtonType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MediaManagerTest {

    @Before
    fun setUp() {
        AutomationCoordinator.reset()
    }

    @Test
    fun testFloatingMenuColorsInRunningState() {
        val state = AutomationCoordinator.State.RUNNING

        // When RUNNING, highlight only START in light red; PAUSE and STOP remain neutral
        assertEquals(
            FloatingMenuColorHelper.COLOR_LIGHT_RED_HIGHLIGHT,
            FloatingMenuColorHelper.getButtonBackgroundColor(MenuButtonType.START, state)
        )
        assertEquals(
            FloatingMenuColorHelper.COLOR_NEUTRAL_BG,
            FloatingMenuColorHelper.getButtonBackgroundColor(MenuButtonType.PAUSE, state)
        )
        assertEquals(
            FloatingMenuColorHelper.COLOR_NEUTRAL_BG,
            FloatingMenuColorHelper.getButtonBackgroundColor(MenuButtonType.STOP, state)
        )
    }

    @Test
    fun testFloatingMenuColorsInPausedState() {
        val state = AutomationCoordinator.State.PAUSED

        // When PAUSED, highlight only PAUSE in light red; START and STOP remain neutral
        assertEquals(
            FloatingMenuColorHelper.COLOR_NEUTRAL_BG,
            FloatingMenuColorHelper.getButtonBackgroundColor(MenuButtonType.START, state)
        )
        assertEquals(
            FloatingMenuColorHelper.COLOR_LIGHT_RED_HIGHLIGHT,
            FloatingMenuColorHelper.getButtonBackgroundColor(MenuButtonType.PAUSE, state)
        )
        assertEquals(
            FloatingMenuColorHelper.COLOR_NEUTRAL_BG,
            FloatingMenuColorHelper.getButtonBackgroundColor(MenuButtonType.STOP, state)
        )
    }

    @Test
    fun testFloatingMenuColorsInStoppedAndIdleStates() {
        val states = listOf(AutomationCoordinator.State.STOPPED, AutomationCoordinator.State.IDLE)

        for (state in states) {
            // In STOPPED and IDLE states, no button should be highlighted (all neutral)
            assertEquals(
                FloatingMenuColorHelper.COLOR_NEUTRAL_BG,
                FloatingMenuColorHelper.getButtonBackgroundColor(MenuButtonType.START, state)
            )
            assertEquals(
                FloatingMenuColorHelper.COLOR_NEUTRAL_BG,
                FloatingMenuColorHelper.getButtonBackgroundColor(MenuButtonType.PAUSE, state)
            )
            assertEquals(
                FloatingMenuColorHelper.COLOR_NEUTRAL_BG,
                FloatingMenuColorHelper.getButtonBackgroundColor(MenuButtonType.STOP, state)
            )
        }
    }

    @Test
    fun testAutomationCoordinatorStateSynchronization() {
        var observedState: AutomationCoordinator.State? = null
        val listener: (AutomationCoordinator.State) -> Unit = { state ->
            observedState = state
        }

        AutomationCoordinator.addListener(listener)

        // Verify initial synchronization
        assertEquals(AutomationCoordinator.State.IDLE, observedState)

        // Verify START
        AutomationCoordinator.start()
        assertEquals(AutomationCoordinator.State.RUNNING, observedState)

        // Verify PAUSE
        AutomationCoordinator.pause()
        assertEquals(AutomationCoordinator.State.PAUSED, observedState)

        // Verify Incoming Call pauses when running
        AutomationCoordinator.start()
        assertEquals(AutomationCoordinator.State.RUNNING, observedState)
        AutomationCoordinator.onIncomingCall()
        assertEquals(AutomationCoordinator.State.PAUSED, observedState)

        // Verify Screen Lock stops automation
        AutomationCoordinator.onScreenLocked()
        assertEquals(AutomationCoordinator.State.STOPPED, observedState)

        // Verify STOP
        AutomationCoordinator.start()
        AutomationCoordinator.stop()
        assertEquals(AutomationCoordinator.State.STOPPED, observedState)

        // Remove listener
        AutomationCoordinator.removeListener(listener)
        AutomationCoordinator.start()
        // observedState should not update after listener removal
        assertEquals(AutomationCoordinator.State.STOPPED, observedState)
    }

    @Test
    fun testGalleryRegistrySupportAndPrivacyBoundaries() {
        // Supported Gallery packages
        val googlePhotos = GalleryRegistry.findAdapter("com.google.android.apps.photos")
        assertNotNull(googlePhotos)
        assertTrue(googlePhotos is GooglePhotosAdapter)

        val samsungGallery = GalleryRegistry.findAdapter("com.sec.android.gallery3d")
        assertNotNull(samsungGallery)
        assertTrue(samsungGallery is SamsungGalleryAdapter)

        val aospGallery = GalleryRegistry.findAdapter("com.android.gallery3d")
        assertNotNull(aospGallery)
        assertTrue(aospGallery is GenericGalleryAdapter)

        // Non-gallery applications MUST return null (banking, messaging, passwords, settings, etc.)
        assertNull(GalleryRegistry.findAdapter("com.chase.sig.android"))
        assertNull(GalleryRegistry.findAdapter("com.whatsapp"))
        assertNull(GalleryRegistry.findAdapter("com.google.android.gm"))
        assertNull(GalleryRegistry.findAdapter("com.android.settings"))
        assertFalse(GalleryRegistry.isSupportedPackage("com.random.unrelated.app"))
    }
}
