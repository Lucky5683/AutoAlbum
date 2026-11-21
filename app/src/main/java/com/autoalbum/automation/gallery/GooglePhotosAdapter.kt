package com.autoalbum.automation.gallery

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Adapter for Google Photos (com.google.android.apps.photos).
 *
 * Requirements:
 * - Detect video completion in looping playback via seekbar progress, loop wrap-around, or duration match.
 * - Distinguish automatic replay from manual seeking and manual pause.
 * - Trigger exactly one next-item navigation action per playback cycle.
 * - Verify that the displayed media item changes via anonymous active-item content description hash.
 * - Stop at the last album item when verification fails twice.
 */
class GooglePhotosAdapter(
    internal val loopDetector: GooglePhotosLoopDetector = GooglePhotosLoopDetector()
) : GalleryAdapter {
    override val packageName: String = "com.google.android.apps.photos"
    override val appName: String = "Google Photos"

    companion object {
        private const val TAG = "AutoAlbum"
        private const val VIEW_PAGER_ID = "com.google.android.apps.photos:id/photo_view_pager"
    }

    private fun isInsidePhotoPager(rootNode: AccessibilityNodeInfo): Boolean {
        return findNodeById(rootNode, VIEW_PAGER_ID) != null
    }

    override fun isPlayerActive(rootNode: AccessibilityNodeInfo): Boolean {
        return isInsidePhotoPager(rootNode) && hasVideoSurfaceOrControls(rootNode)
    }

    override fun isPhoto(rootNode: AccessibilityNodeInfo): Boolean {
        if (!isInsidePhotoPager(rootNode)) {
            return false
        }
        if (hasVideoSurfaceOrControls(rootNode)) {
            return false
        }
        return true
    }

    override fun isVideoPlaying(rootNode: AccessibilityNodeInfo): Boolean {
        val pauseBtn = findNodeWithDescription(rootNode, listOf("Pause video", "Pause"))
        if (pauseBtn != null) {
            pauseBtn.recycle()
            return true
        }
        // When controls fade out during playback, SurfaceView remains active and video is not paused
        return hasVideoSurfaceOrControls(rootNode) && !isVideoPaused(rootNode)
    }

    override fun isVideoPaused(rootNode: AccessibilityNodeInfo): Boolean {
        val playBtn = findNodeWithDescription(rootNode, listOf("Play video", "Play"))
        val isPaused = playBtn != null
        playBtn?.recycle()
        return isPaused
    }

    override fun isVideoCompleted(rootNode: AccessibilityNodeInfo): Boolean {
        // 1. Sync active media signature
        val activeSig = getActiveItemSignature(rootNode)
        loopDetector.updateActiveSignature(activeSig)

        // 2. Check paused & playing states
        val paused = isVideoPaused(rootNode)
        val playing = isVideoPlaying(rootNode)

        // 3. Extract time text if available
        val times = extractTimeNodes(rootNode)
        val currentSec = times.getOrNull(0)
        val totalSec = times.getOrNull(1)

        // Update loop detector play state & known duration
        loopDetector.onPlayStateChanged(isPlaying = playing, isPaused = paused, totalSec = totalSec)

        // 4. Check replay control
        val replayNode = findNodeWithDescription(
            rootNode,
            listOf("Replay", "Restart video", "Replay video", "Watch again", "Play again")
        )
        val hasReplay = replayNode != null
        replayNode?.recycle()

        // 5. Seekbar progress
        val progress = findSeekbarProgress(rootNode)

        Log.d(TAG, "[GooglePhotosAdapter] sig='$activeSig' paused=$paused playing=$playing progress=$progress currentSec=$currentSec totalSec=$totalSec knownDur=${loopDetector.knownDurationSeconds} elapsedMs=${loopDetector.getEffectiveElapsedMs()}")

        val completed = loopDetector.checkCompletion(
            progress = progress,
            isPaused = paused,
            hasReplayControl = hasReplay,
            currentSeconds = currentSec,
            totalSeconds = totalSec
        )

        if (completed) {
            Log.i(TAG, "[CompletionDetection] Video completion confirmed in Google Photos for item '$activeSig'.")
        }

        return completed
    }

    override fun findNextButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""
            val isNext = (desc == "next" || desc == "next video" || desc == "next item" || id.contains("next")) &&
                         !desc.contains("forward")
            if (isNext && node.isClickable) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    override fun performNextAction(rootNode: AccessibilityNodeInfo): Boolean {
        val viewPager = findNodeById(rootNode, VIEW_PAGER_ID)
        if (viewPager != null) {
            val scrolled = viewPager.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            viewPager.recycle()
            if (scrolled) {
                Log.i(TAG, "[NavigationAction] ViewPager ACTION_SCROLL_FORWARD performed successfully.")
                return true
            }
        }
        return false
    }

    override fun getActiveItemSignature(rootNode: AccessibilityNodeInfo): String? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        val rect = Rect()
        var bestDescSig: String? = null
        var bestDist = Int.MAX_VALUE
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val desc = node.contentDescription?.toString() ?: ""
            if (desc.contains("Video taken on", true) || desc.contains("Photo taken on", true) ||
                desc.contains("202", true) || desc.contains("AM", true) || desc.contains("PM", true)
            ) {
                node.getBoundsInScreen(rect)
                val dist = kotlin.math.abs(rect.centerX() - 540)
                if (dist < bestDist) {
                    bestDist = dist
                    bestDescSig = "gp_${desc.hashCode()}"
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        if (bestDescSig != null) {
            return bestDescSig
        }

        // Active page subtree structural fingerprint of the centered ViewPager child
        val viewPager = findNodeById(rootNode, VIEW_PAGER_ID)
        var centerChildFingerprint: String? = null
        if (viewPager != null) {
            var minDistance = Int.MAX_VALUE
            var bestChild: AccessibilityNodeInfo? = null
            for (i in 0 until viewPager.childCount) {
                val child = viewPager.getChild(i)
                if (child != null) {
                    child.getBoundsInScreen(rect)
                    val dist = kotlin.math.abs(rect.centerX() - 540)
                    if (dist < minDistance) {
                        minDistance = dist
                        bestChild?.recycle()
                        bestChild = child
                    } else {
                        child.recycle()
                    }
                }
            }
            if (bestChild != null) {
                val sb = StringBuilder()
                val childQueue = ArrayDeque<AccessibilityNodeInfo>()
                childQueue.add(bestChild)
                var count = 0
                while (childQueue.isNotEmpty() && count < 25) {
                    val n = childQueue.removeFirst()
                    count++
                    n.getBoundsInScreen(rect)
                    sb.append(n.className).append("@").append(rect.toShortString()).append(";")
                    n.text?.let { sb.append("t:").append(it).append(";") }
                    n.contentDescription?.let { sb.append("d:").append(it).append(";") }
                    for (c in 0 until n.childCount) {
                        n.getChild(c)?.let { childQueue.add(it) }
                    }
                }
                bestChild.recycle()
                centerChildFingerprint = "gp_page_${sb.toString().hashCode()}"
            }
            viewPager.recycle()
        }

        val times = extractTimeNodes(rootNode)
        val dur = times.getOrNull(1) ?: loopDetector.knownDurationSeconds
        return if (centerChildFingerprint != null) {
            "${centerChildFingerprint}_dur_${dur}"
        } else {
            "gp_dur_${dur}"
        }
    }

    override fun needsControlAwakening(): Boolean {
        return loopDetector.knownDurationSeconds == 0 && !loopDetector.hasAwakenedControls
    }

    override fun onControlsAwakened() {
        loopDetector.hasAwakenedControls = true
    }

    override fun onNavigationFailed() {
        Log.w(TAG, "[GooglePhotos] Navigation attempt unconfirmed. Resetting latch for retry.")
        loopDetector.onNavigationFailed()
    }

    override fun resetSession() {
        loopDetector.reset()
    }

    private fun findSeekbarProgress(root: AccessibilityNodeInfo): Float? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val className = node.className?.toString() ?: ""
            if (className.contains("SeekBar", true) || className.contains("ProgressBar", true)) {
                val range = node.rangeInfo
                if (range != null && range.max > 0f) {
                    return range.current / range.max
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun extractTimeNodes(root: AccessibilityNodeInfo): List<Int> {
        val times = mutableListOf<Int>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val timeRegex = Regex("""^(\d+):(\d{2})$""")
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString() ?: ""
            val match = timeRegex.find(text)
            if (match != null) {
                val minutes = match.groupValues[1].toIntOrNull() ?: 0
                val seconds = match.groupValues[2].toIntOrNull() ?: 0
                times.add(minutes * 60 + seconds)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return times
    }

    private fun findNodeById(root: AccessibilityNodeInfo, targetId: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.viewIdResourceName?.equals(targetId, ignoreCase = true) == true) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun hasVideoSurfaceOrControls(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val className = node.className?.toString() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""

            if (className.contains("SurfaceView", true) || className.contains("TextureView", true) || className.contains("VideoView", true)) {
                return true
            }
            if (id.contains("video", true) || id.contains("player", true) || id.contains("seekbar", true)) {
                return true
            }
            if (desc.contains("video", true) || desc.contains("replay", true) || desc.contains("seek", true)) {
                return true
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun findNodeWithAny(root: AccessibilityNodeInfo, tokens: List<String>): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""
            val cls = node.className?.toString()?.lowercase() ?: ""

            val matches = tokens.any { token ->
                text.contains(token) || desc.contains(token) || id.contains(token) || cls.contains(token)
            }
            if (matches) return node

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findNodeWithDescription(root: AccessibilityNodeInfo, targets: List<String>): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val desc = node.contentDescription?.toString() ?: ""
            if (targets.any { desc.equals(it, ignoreCase = true) }) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }
}
