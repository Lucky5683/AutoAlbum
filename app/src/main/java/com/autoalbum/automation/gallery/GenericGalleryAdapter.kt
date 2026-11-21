package com.autoalbum.automation.gallery

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Generic fallback adapter for AOSP Gallery, Google Gallery Go, Simple Mobile Tools Gallery,
 * Fossify Gallery, MX Player, and other compatible Android video players.
 */
class GenericGalleryAdapter(
    internal val loopDetector: UniversalPlaybackDetector = UniversalPlaybackDetector()
) : GalleryAdapter {

    override val packageName: String = "generic"
    override val appName: String = "Generic Gallery"

    companion object {
        private const val TAG = "AutoAlbum"
        private val SUPPORTED_PACKAGES = setOf(
            "com.android.gallery3d",
            "com.google.android.apps.photosgo",
            "com.simplemobiletools.gallery",
            "com.simplemobiletools.gallery.pro",
            "org.fossify.gallery",
            "com.mxtech.videoplayer.ad"
        )
    }

    override fun isSupported(pkg: String): Boolean = SUPPORTED_PACKAGES.contains(pkg)

    override fun isPlayerActive(rootNode: AccessibilityNodeInfo): Boolean {
        // Guard: Do not interpret album grids or directory listings as active video player
        if (VideoPlayerNodeHelper.isLikelyListOrGridScreen(rootNode)) {
            return false
        }
        return VideoPlayerNodeHelper.hasVideoControlsOrSurface(rootNode)
    }

    override fun isPhoto(rootNode: AccessibilityNodeInfo): Boolean {
        if (isPlayerActive(rootNode)) {
            return false
        }
        if (!VideoPlayerNodeHelper.isLikelyListOrGridScreen(rootNode)) {
            return rootNode.className?.contains("ImageView", true) == true
        }
        return false
    }

    override fun isVideoPlaying(rootNode: AccessibilityNodeInfo): Boolean {
        if (VideoPlayerNodeHelper.isPauseButtonPresent(rootNode)) {
            return true
        }
        return VideoPlayerNodeHelper.hasVideoSurface(rootNode) && !isVideoPaused(rootNode)
    }

    override fun isVideoPaused(rootNode: AccessibilityNodeInfo): Boolean {
        return VideoPlayerNodeHelper.isPlayButtonPresent(rootNode) && !isVideoCompleted(rootNode)
    }

    override fun isVideoCompleted(rootNode: AccessibilityNodeInfo): Boolean {
        val activeSig = getActiveItemSignature(rootNode)
        loopDetector.updateActiveSignature(activeSig)

        val paused = isVideoPaused(rootNode)
        val playing = isVideoPlaying(rootNode)

        val times = VideoPlayerNodeHelper.extractTimeNodes(rootNode)
        val currentSec = times.getOrNull(0)
        val totalSec = times.getOrNull(1)

        loopDetector.onPlayStateChanged(isPlaying = playing, isPaused = paused, totalSec = totalSec)

        val hasReplay = VideoPlayerNodeHelper.hasReplayControl(rootNode)
        val progress = VideoPlayerNodeHelper.findSeekbarProgress(rootNode)

        Log.d(TAG, "[GenericGalleryAdapter] sig='$activeSig' paused=$paused playing=$playing progress=$progress currentSec=$currentSec totalSec=$totalSec")

        val completed = loopDetector.checkCompletion(
            progress = progress,
            isPaused = paused,
            hasReplayControl = hasReplay,
            currentSeconds = currentSec,
            totalSeconds = totalSec
        )

        if (completed) {
            Log.i(TAG, "[CompletionDetection] Video completion confirmed in Generic Gallery for item '$activeSig'.")
        }

        return completed
    }

    override fun findNextButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return VideoPlayerNodeHelper.findNextButton(rootNode)
    }

    override fun getActiveItemSignature(rootNode: AccessibilityNodeInfo): String? {
        return VideoPlayerNodeHelper.extractGenericMediaSignature(rootNode, "generic")
    }

    override fun onNavigationFailed() {
        Log.w(TAG, "[GenericGallery] Navigation unconfirmed. Resetting latch for retry.")
        loopDetector.onNavigationFailed()
    }

    override fun resetSession() {
        loopDetector.reset()
    }
}
