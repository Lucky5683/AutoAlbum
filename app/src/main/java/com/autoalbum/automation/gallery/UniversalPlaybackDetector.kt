package com.autoalbum.automation.gallery

/**
 * Universal playback detector and loop wrap-around engine for Android video players.
 *
 * Implements multiple complementary completion signals:
 * 1. Seekbar progress reaching the end (>= 0.96f)
 * 2. Loop wrap-around transition (max observed progress >= 0.80f dropping to <= 0.15f)
 * 3. Explicit replay / restart control becoming visible
 * 4. Duration text matching (current time equals or exceeds total duration - 1s)
 * 5. Measured active playback time when duration is known
 *
 * Safeguards:
 * - Intentional user pause strictly suppresses completion and holds automation
 * - Completion is latched once per video cycle until active media item signature changes
 * - Navigation failure resets latch to allow one retry before terminal stop
 */
open class UniversalPlaybackDetector {
    var currentItemSignature: String? = null
        private set
    var maxObservedProgress: Float = 0f
        private set
    var hasNavigatedForCurrentItem: Boolean = false
        private set
    var lastLoopDetectionTimestamp: Long = 0L
        private set
    var hasAwakenedControls: Boolean = false

    // Duration and elapsed tracking
    var knownDurationSeconds: Int = 0
        private set
    private var playbackAccumulatedMs: Long = 0L
    private var lastPlayResumeTimestamp: Long = 0L
    private var isCurrentlyPlaying: Boolean = false

    fun updateActiveSignature(signature: String?) {
        if (!signature.isNullOrEmpty() && signature != currentItemSignature) {
            currentItemSignature = signature
            maxObservedProgress = 0f
            hasNavigatedForCurrentItem = false
            knownDurationSeconds = 0
            hasAwakenedControls = false
            playbackAccumulatedMs = 0L
            lastPlayResumeTimestamp = System.currentTimeMillis()
            isCurrentlyPlaying = true
        }
    }

    fun onPlayStateChanged(isPlaying: Boolean, isPaused: Boolean, totalSec: Int? = null) {
        val now = System.currentTimeMillis()
        if (totalSec != null && totalSec > 0 && knownDurationSeconds == 0) {
            knownDurationSeconds = totalSec
        }

        if (isPaused) {
            if (isCurrentlyPlaying) {
                playbackAccumulatedMs += (now - lastPlayResumeTimestamp)
                isCurrentlyPlaying = false
            }
        } else if (isPlaying) {
            if (!isCurrentlyPlaying) {
                lastPlayResumeTimestamp = now
                isCurrentlyPlaying = true
            }
        }
    }

    fun getEffectiveElapsedMs(): Long {
        return if (isCurrentlyPlaying) {
            playbackAccumulatedMs + (System.currentTimeMillis() - lastPlayResumeTimestamp)
        } else {
            playbackAccumulatedMs
        }
    }

    fun checkCompletion(
        progress: Float?,
        isPaused: Boolean,
        hasReplayControl: Boolean,
        currentSeconds: Int? = null,
        totalSeconds: Int? = null,
        currentTimestamp: Long = System.currentTimeMillis()
    ): Boolean {
        // Guard 1: Prevent repeated navigation for the same item cycle
        if (hasNavigatedForCurrentItem) {
            return false
        }

        // Guard 2: Manual pause takes strict precedence; hold automation
        if (isPaused) {
            return false
        }

        // 1. Explicit Replay button / icon
        if (hasReplayControl) {
            hasNavigatedForCurrentItem = true
            return true
        }

        // 2. Seekbar progress evaluation
        if (progress != null && progress in 0.0f..1.5f) {
            if (progress > maxObservedProgress) {
                maxObservedProgress = progress
            }

            // Condition 2A: Seekbar progress >= 0.96f
            if (progress >= 0.96f) {
                hasNavigatedForCurrentItem = true
                return true
            }

            // Condition 2B: Loop wrap-around transition (was >= 0.80f, now <= 0.15f)
            if (maxObservedProgress >= 0.80f && progress <= 0.15f) {
                if (currentTimestamp - lastLoopDetectionTimestamp > 2000L) {
                    lastLoopDetectionTimestamp = currentTimestamp
                    hasNavigatedForCurrentItem = true
                    return true
                }
            }
        }

        // 3. Duration text match
        if (currentSeconds != null && totalSeconds != null && totalSeconds > 0) {
            if (knownDurationSeconds == 0) {
                knownDurationSeconds = totalSeconds
            }
            if (currentSeconds >= (totalSeconds - 1)) {
                hasNavigatedForCurrentItem = true
                return true
            }
        }

        // 4. Elapsed playback timer (when total duration is known)
        if (knownDurationSeconds > 0 && isCurrentlyPlaying) {
            val elapsedMs = getEffectiveElapsedMs()
            val targetMs = (knownDurationSeconds * 1000L) - 400L
            if (elapsedMs >= targetMs) {
                hasNavigatedForCurrentItem = true
                return true
            }
        }

        return false
    }

    fun onNavigationFailed() {
        hasNavigatedForCurrentItem = false
    }

    fun reset() {
        currentItemSignature = null
        maxObservedProgress = 0f
        hasNavigatedForCurrentItem = false
        lastLoopDetectionTimestamp = 0L
        knownDurationSeconds = 0
        hasAwakenedControls = false
        playbackAccumulatedMs = 0L
        lastPlayResumeTimestamp = 0L
        isCurrentlyPlaying = false
    }
}
