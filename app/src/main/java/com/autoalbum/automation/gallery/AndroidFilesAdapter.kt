package com.autoalbum.automation.gallery

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Adapter for Android Files / File Manager / Downloads applications:
 * - com.google.android.documentsui (Android DocumentsUI)
 * - com.android.documentsui (AOSP DocumentsUI)
 * - com.google.android.apps.nbu.files (Files by Google)
 * - com.android.providers.downloads.ui (Downloads)
 */
class AndroidFilesAdapter(
    internal val loopDetector: UniversalPlaybackDetector = UniversalPlaybackDetector()
) : GalleryAdapter {

    override val packageName: String = "com.google.android.documentsui"
    override val appName: String = "Android Files"

    companion object {
        private const val TAG = "AutoAlbum"
        private val SUPPORTED_PACKAGES = setOf(
            "com.google.android.documentsui",
            "com.android.documentsui",
            "com.google.android.apps.nbu.files",
            "com.android.providers.downloads.ui"
        )
    }

    override fun isSupported(pkg: String): Boolean = SUPPORTED_PACKAGES.contains(pkg)

    override fun isPlayerActive(rootNode: AccessibilityNodeInfo): Boolean {
        // Guard: Do not interpret file manager directory listings as active video player
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

        Log.d(TAG, "[AndroidFilesAdapter] sig='$activeSig' paused=$paused playing=$playing progress=$progress currentSec=$currentSec totalSec=$totalSec")

        val completed = loopDetector.checkCompletion(
            progress = progress,
            isPaused = paused,
            hasReplayControl = hasReplay,
            currentSeconds = currentSec,
            totalSeconds = totalSec
        )

        if (completed) {
            Log.i(TAG, "[CompletionDetection] Video completion confirmed in Android Files for item '$activeSig'.")
        }

        return completed
    }

    override fun findNextButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return VideoPlayerNodeHelper.findNextButton(rootNode)
    }

    override fun getActiveItemSignature(rootNode: AccessibilityNodeInfo): String? {
        return VideoPlayerNodeHelper.extractGenericMediaSignature(rootNode, "files")
    }

    override fun onNavigationFailed() {
        Log.w(TAG, "[AndroidFiles] Navigation unconfirmed. Resetting latch for retry.")
        loopDetector.onNavigationFailed()
    }

    override fun resetSession() {
        loopDetector.reset()
    }
}
