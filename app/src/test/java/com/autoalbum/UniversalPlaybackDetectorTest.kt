package com.autoalbum

import com.autoalbum.automation.gallery.GalleryRegistry
import com.autoalbum.automation.gallery.UniversalPlaybackDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests verifying Universal Video Playback Navigation requirements:
 * 1. Multi-gallery registry coverage (Google Photos, Vivo, Samsung, Android Files, Generic).
 * 2. Seekbar progress completion signal (>= 0.96f).
 * 3. Loop wrap-around completion signal (>= 0.80f -> <= 0.15f).
 * 4. Duration text matching signal (current >= total - 1).
 * 5. Measured active playback elapsed time signal.
 * 6. Explicit replay control signal.
 * 7. Manual pause suppression and state preservation.
 * 8. Latch persistence and reset on media change.
 * 9. Navigation failure retry reset.
 */
class UniversalPlaybackDetectorTest {

    private lateinit var detector: UniversalPlaybackDetector

    @Before
    fun setUp() {
        detector = UniversalPlaybackDetector()
    }

    @Test
    fun testGalleryRegistrySupportsAllMandatedPackages() {
        val mandatedPackages = listOf(
            "com.google.android.apps.photos",
            "com.vivo.gallery",
            "com.android.VideoPlayer",
            "com.sec.android.gallery3d",
            "com.google.android.documentsui",
            "com.android.documentsui",
            "com.google.android.apps.nbu.files",
            "com.android.providers.downloads.ui",
            "com.android.gallery3d",
            "com.google.android.apps.photosgo",
            "com.simplemobiletools.gallery",
            "com.simplemobiletools.gallery.pro",
            "org.fossify.gallery",
            "com.mxtech.videoplayer.ad"
        )

        for (pkg in mandatedPackages) {
            assertTrue("Package '$pkg' must be supported by GalleryRegistry", GalleryRegistry.isSupportedPackage(pkg))
            assertNotNull("Package '$pkg' must resolve to an adapter", GalleryRegistry.findAdapter(pkg))
        }

        // Non-gallery packages must be strictly rejected
        val forbiddenPackages = listOf(
            "com.whatsapp",
            "com.android.settings",
            "com.chase.sig.android",
            "com.google.android.chrome"
        )
        for (pkg in forbiddenPackages) {
            assertFalse("Package '$pkg' must NOT be supported", GalleryRegistry.isSupportedPackage(pkg))
        }
    }

    @Test
    fun testSeekbarProgressSignal() {
        detector.updateActiveSignature("media_sample_1")
        assertFalse(detector.checkCompletion(progress = 0.50f, isPaused = false, hasReplayControl = false))
        assertFalse(detector.checkCompletion(progress = 0.95f, isPaused = false, hasReplayControl = false))
        assertTrue(detector.checkCompletion(progress = 0.97f, isPaused = false, hasReplayControl = false))
        // Latched
        assertFalse(detector.checkCompletion(progress = 1.0f, isPaused = false, hasReplayControl = false))
    }

    @Test
    fun testLoopWrapAroundSignal() {
        detector.updateActiveSignature("media_sample_2")
        detector.checkCompletion(progress = 0.85f, isPaused = false, hasReplayControl = false)
        // Immediate wrap-around
        val looped = detector.checkCompletion(
            progress = 0.05f,
            isPaused = false,
            hasReplayControl = false,
            currentTimestamp = 10000L
        )
        assertTrue(looped)
    }

    @Test
    fun testDurationTextMatchingSignal() {
        detector.updateActiveSignature("media_sample_3")
        assertFalse(detector.checkCompletion(progress = null, isPaused = false, hasReplayControl = false, currentSeconds = 10, totalSeconds = 15))
        assertTrue(detector.checkCompletion(progress = null, isPaused = false, hasReplayControl = false, currentSeconds = 15, totalSeconds = 15))
    }

    @Test
    fun testReplayControlSignal() {
        detector.updateActiveSignature("media_sample_4")
        assertTrue(detector.checkCompletion(progress = 0.10f, isPaused = false, hasReplayControl = true))
    }

    @Test
    fun testManualPauseSuppressesAllSignals() {
        detector.updateActiveSignature("media_sample_5")
        assertFalse(detector.checkCompletion(progress = 0.99f, isPaused = true, hasReplayControl = false))
        assertFalse(detector.checkCompletion(progress = 0.05f, isPaused = true, hasReplayControl = true))
        assertFalse(detector.checkCompletion(progress = null, isPaused = true, hasReplayControl = false, currentSeconds = 20, totalSeconds = 20))
    }

    @Test
    fun testMeasuredActivePlaybackElapsedSignal() {
        detector.updateActiveSignature("media_sample_6")
        detector.onPlayStateChanged(isPlaying = true, isPaused = false, totalSec = 1)
        Thread.sleep(700)
        assertTrue(detector.checkCompletion(progress = null, isPaused = false, hasReplayControl = false))
    }

    @Test
    fun testItemChangeAndRetryLifecycle() {
        detector.updateActiveSignature("item_A")
        assertTrue(detector.checkCompletion(progress = 0.98f, isPaused = false, hasReplayControl = false))
        assertFalse(detector.checkCompletion(progress = 0.98f, isPaused = false, hasReplayControl = false))

        // Failed verification triggers retry
        detector.onNavigationFailed()
        assertTrue(detector.checkCompletion(progress = 0.98f, isPaused = false, hasReplayControl = false))

        // Next item resets cleanly
        detector.updateActiveSignature("item_B")
        assertEquals(0f, detector.maxObservedProgress, 0.001f)
        assertFalse(detector.hasNavigatedForCurrentItem)
    }
}
