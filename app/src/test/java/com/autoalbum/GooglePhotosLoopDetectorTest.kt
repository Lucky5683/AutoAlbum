package com.autoalbum

import com.autoalbum.automation.gallery.GooglePhotosLoopDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests verifying Google Photos looping playback completion detection heuristics.
 *
 * Requirements tested:
 * 1. Progress reaching >= 0.96f triggers completion.
 * 2. Loop wrap-around transition: maxProgress >= 0.80f dropping to <= 0.15f triggers completion.
 * 3. Distinguish automatic replay from manual seeking (seeking back from mid-video e.g. 0.50 to 0.10 does not trigger).
 * 4. Manual pause strictly suppresses completion and pauses playback accumulator.
 * 5. Completion is latched: only triggers once per playback cycle until active item changes.
 * 6. Duration text matching (e.g. 19 / 19s) triggers completion.
 * 7. Active item signature change resets latch, progress, and duration tracking.
 * 8. onNavigationFailed() resets latch allowing retry.
 * 9. Playback time accumulator reaches known duration when controls are faded out.
 */
class GooglePhotosLoopDetectorTest {

    private lateinit var detector: GooglePhotosLoopDetector

    @Before
    fun setUp() {
        detector = GooglePhotosLoopDetector()
    }

    @Test
    fun testProgressReachingEndTriggersCompletion() {
        detector.updateActiveSignature("item_video_1")

        // Mid-playback: 50%
        val midResult = detector.checkCompletion(progress = 0.50f, isPaused = false, hasReplayControl = false)
        assertFalse(midResult)
        assertFalse(detector.hasNavigatedForCurrentItem)

        // Progress near end: 95%
        val nearEndResult = detector.checkCompletion(progress = 0.95f, isPaused = false, hasReplayControl = false)
        assertFalse(nearEndResult)

        // Progress reached end: 98% (as observed in Google Photos live log: 0.9802 - 1.0005)
        val endResult = detector.checkCompletion(progress = 0.98f, isPaused = false, hasReplayControl = false)
        assertTrue(endResult)
        assertTrue(detector.hasNavigatedForCurrentItem)

        // Subsequent calls for same item cycle must return false (latched)
        val latchedResult = detector.checkCompletion(progress = 1.0f, isPaused = false, hasReplayControl = false)
        assertFalse(latchedResult)
    }

    @Test
    fun testLoopWrapAroundTransitionTriggersCompletion() {
        detector.updateActiveSignature("item_video_1")

        // Progress climbed to 88%
        detector.checkCompletion(progress = 0.88f, isPaused = false, hasReplayControl = false)
        assertEquals(0.88f, detector.maxObservedProgress, 0.001f)
        assertFalse(detector.hasNavigatedForCurrentItem)

        // Google Photos automatically replays: progress drops to 0.02 (as observed: 1.0005 -> 0.0106)
        val loopResult = detector.checkCompletion(
            progress = 0.02f,
            isPaused = false,
            hasReplayControl = false,
            currentTimestamp = 5000L
        )
        assertTrue(loopResult)
        assertTrue(detector.hasNavigatedForCurrentItem)

        // Debounced: wrap-around again within 2 seconds is rejected
        detector.onNavigationFailed() // reset latch for test
        val immediateRepeat = detector.checkCompletion(
            progress = 0.03f,
            isPaused = false,
            hasReplayControl = false,
            currentTimestamp = 6000L // 1000ms later (< 2000ms)
        )
        assertFalse(immediateRepeat)
    }

    @Test
    fun testMidVideoSeekBackwardsDoesNotTriggerCompletion() {
        detector.updateActiveSignature("item_video_1")

        // User watched up to 50%
        detector.checkCompletion(progress = 0.50f, isPaused = false, hasReplayControl = false)
        assertEquals(0.50f, detector.maxObservedProgress, 0.001f)

        // User manually scrubs backward to 10%
        val scrubResult = detector.checkCompletion(progress = 0.10f, isPaused = false, hasReplayControl = false)
        assertFalse("Seeking backward from mid-video must not trigger completion", scrubResult)
        assertFalse(detector.hasNavigatedForCurrentItem)
    }

    @Test
    fun testManualPauseSuppressesCompletion() {
        detector.updateActiveSignature("item_video_1")

        // Video at 99%, but user manually pressed Pause ("Play" button visible)
        val pausedAtEnd = detector.checkCompletion(progress = 0.99f, isPaused = true, hasReplayControl = false)
        assertFalse("Manual pause must suppress completion and hold automation", pausedAtEnd)
        assertFalse(detector.hasNavigatedForCurrentItem)
    }

    @Test
    fun testDurationTextMatchTriggersCompletion() {
        detector.updateActiveSignature("item_video_1")

        // 13s of 19s
        val midTime = detector.checkCompletion(
            progress = null,
            isPaused = false,
            hasReplayControl = false,
            currentSeconds = 13,
            totalSeconds = 19
        )
        assertFalse(midTime)

        // 19s of 19s (or 18s of 19s)
        val endTime = detector.checkCompletion(
            progress = null,
            isPaused = false,
            hasReplayControl = false,
            currentSeconds = 19,
            totalSeconds = 19
        )
        assertTrue(endTime)
        assertTrue(detector.hasNavigatedForCurrentItem)
    }

    @Test
    fun testActiveSignatureChangeResetsStateForNextVideo() {
        detector.updateActiveSignature("item_video_1")
        detector.checkCompletion(progress = 0.98f, isPaused = false, hasReplayControl = false)
        assertTrue(detector.hasNavigatedForCurrentItem)

        // Navigation occurs -> active item signature updates to video 2
        detector.updateActiveSignature("item_video_2")
        assertEquals("item_video_2", detector.currentItemSignature)
        assertEquals(0f, detector.maxObservedProgress, 0.001f)
        assertFalse(detector.hasNavigatedForCurrentItem)

        // Video 2 starts playing from 0%
        val video2Start = detector.checkCompletion(progress = 0.05f, isPaused = false, hasReplayControl = false)
        assertFalse(video2Start)
    }

    @Test
    fun testNavigationFailureRetryReset() {
        detector.updateActiveSignature("item_video_1")
        detector.checkCompletion(progress = 0.98f, isPaused = false, hasReplayControl = false)
        assertTrue(detector.hasNavigatedForCurrentItem)

        // Verification detects media unchanged on first attempt -> retry requested
        detector.onNavigationFailed()
        assertFalse(detector.hasNavigatedForCurrentItem)

        // Allows one retry
        val retryResult = detector.checkCompletion(progress = 0.98f, isPaused = false, hasReplayControl = false)
        assertTrue(retryResult)
    }

    @Test
    fun testPlaybackStateAccumulator() {
        detector.updateActiveSignature("item_video_1")
        detector.onPlayStateChanged(isPlaying = true, isPaused = false, totalSec = 15)
        assertEquals(15, detector.knownDurationSeconds)

        // Pause after some time
        detector.onPlayStateChanged(isPlaying = false, isPaused = true)
        val elapsedWhenPaused = detector.getEffectiveElapsedMs()

        // Wait while paused -> effective elapsed must NOT advance
        Thread.sleep(50)
        assertEquals(elapsedWhenPaused, detector.getEffectiveElapsedMs())

        // Resume playing
        detector.onPlayStateChanged(isPlaying = true, isPaused = false)
        Thread.sleep(50)
        assertTrue(detector.getEffectiveElapsedMs() > elapsedWhenPaused)
    }

    @Test
    fun testSessionReset() {
        detector.updateActiveSignature("item_video_1")
        detector.checkCompletion(progress = 0.98f, isPaused = false, hasReplayControl = false)

        detector.reset()
        assertEquals(null, detector.currentItemSignature)
        assertEquals(0f, detector.maxObservedProgress, 0.001f)
        assertFalse(detector.hasNavigatedForCurrentItem)
        assertEquals(0L, detector.lastLoopDetectionTimestamp)
        assertEquals(0, detector.knownDurationSeconds)
    }
}
