package com.autoalbum.automation.gallery

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Universal accessibility node helper functions for Android video players and galleries.
 * Isolates node tree inspection and prevents memory leaks by recycling non-matched nodes.
 */
object VideoPlayerNodeHelper {

    private val TIME_REGEX_MM_SS = Regex("""^(\d+):(\d{2})$""")
    private val TIME_REGEX_HH_MM_SS = Regex("""^(\d+):(\d{2}):(\d{2})$""")

    /**
     * Inspects whether the hierarchy represents an album grid, file directory listing,
     * search page, or settings screen rather than an active video player.
     * Safeguard mandated by specification: never interpret directory listings or grids as active video players.
     */
    fun isLikelyListOrGridScreen(root: AccessibilityNodeInfo): Boolean {
        var hasDirectoryOrGrid = false
        var hasActiveVideoSurface = false

        traverseNodes(root) { node ->
            val className = node.className?.toString() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""

            if (className.contains("SurfaceView", true) ||
                className.contains("TextureView", true) ||
                className.contains("VideoView", true)
            ) {
                hasActiveVideoSurface = true
            }

            if (id.contains("dir_list") || id.contains("file_list") ||
                id.contains("album_list") || id.contains("thumbnail_grid") ||
                id.contains("recycler_view") || id.contains("grid_view") ||
                id.contains("search_results") || id.contains("settings")
            ) {
                hasDirectoryOrGrid = true
            }
        }

        return hasDirectoryOrGrid && !hasActiveVideoSurface
    }

    /**
     * Checks if a video surface (SurfaceView, TextureView, VideoView) is present in the tree.
     */
    fun hasVideoSurface(root: AccessibilityNodeInfo): Boolean {
        var found = false
        traverseNodes(root) { node ->
            val className = node.className?.toString() ?: ""
            if (className.contains("SurfaceView", true) ||
                className.contains("TextureView", true) ||
                className.contains("VideoView", true)
            ) {
                found = true
            }
        }
        return found
    }

    /**
     * Checks if the hierarchy contains video controls (SeekBar, play/pause, media player layout)
     * or a video rendering surface.
     */
    fun hasVideoControlsOrSurface(root: AccessibilityNodeInfo): Boolean {
        var found = false
        traverseNodes(root) { node ->
            val className = node.className?.toString() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""

            if (className.contains("SurfaceView", true) ||
                className.contains("TextureView", true) ||
                className.contains("VideoView", true) ||
                id.contains("video", true) ||
                id.contains("player", true) ||
                id.contains("exo", true) ||
                id.contains("mediacontroller", true) ||
                id.contains("seekbar", true) ||
                desc.contains("video", true) ||
                desc.contains("pause", true) ||
                (desc.contains("play", true) && !desc.contains("google play", true))
            ) {
                found = true
            }
        }
        return found
    }

    /**
     * Finds normalized seekbar progress [0.0f..1.0f] if a SeekBar or ProgressBar with rangeInfo is present.
     */
    fun findSeekbarProgress(root: AccessibilityNodeInfo): Float? {
        var progress: Float? = null
        traverseNodes(root) { node ->
            val className = node.className?.toString() ?: ""
            if (className.contains("SeekBar", true) || className.contains("ProgressBar", true)) {
                val range = node.rangeInfo
                if (range != null && range.max > 0f) {
                    val p = range.current / range.max
                    if (progress == null || p > progress!!) {
                        progress = p
                    }
                }
            }
        }
        return progress
    }

    /**
     * Extracts playback times in seconds from text nodes (e.g. "0:14", "1:25", "0:02:15").
     * Returns a list of timestamps, typically [currentSeconds, totalSeconds].
     */
    fun extractTimeNodes(root: AccessibilityNodeInfo): List<Int> {
        val times = mutableListOf<Int>()
        traverseNodes(root) { node ->
            val text = node.text?.toString() ?: ""
            val mmSsMatch = TIME_REGEX_MM_SS.find(text)
            if (mmSsMatch != null) {
                val m = mmSsMatch.groupValues[1].toIntOrNull() ?: 0
                val s = mmSsMatch.groupValues[2].toIntOrNull() ?: 0
                times.add(m * 60 + s)
            } else {
                val hhMmSsMatch = TIME_REGEX_HH_MM_SS.find(text)
                if (hhMmSsMatch != null) {
                    val h = hhMmSsMatch.groupValues[1].toIntOrNull() ?: 0
                    val m = hhMmSsMatch.groupValues[2].toIntOrNull() ?: 0
                    val s = hhMmSsMatch.groupValues[3].toIntOrNull() ?: 0
                    times.add(h * 3600 + m * 60 + s)
                }
            }
        }
        return times
    }

    /**
     * Checks if a Pause button is present on screen (indicating video is currently paused or controls show pause).
     */
    fun isPauseButtonPresent(root: AccessibilityNodeInfo): Boolean {
        var found = false
        traverseNodes(root) { node ->
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""
            if (desc == "pause" || desc == "pause video" || desc.contains("pause") ||
                text == "pause" || id.contains("btn_pause") || id.contains("pause_btn")
            ) {
                found = true
            }
        }
        return found
    }

    /**
     * Checks if a Play button is present on screen (indicating video is paused and ready to play).
     */
    fun isPlayButtonPresent(root: AccessibilityNodeInfo): Boolean {
        var found = false
        traverseNodes(root) { node ->
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""
            if ((desc == "play" || desc == "play video" || (desc.contains("play") && !desc.contains("playlist") && !desc.contains("google play"))) ||
                text == "play" || id.contains("btn_play") || id.contains("play_btn")
            ) {
                found = true
            }
        }
        return found
    }

    /**
     * Checks if an explicit replay or restart button/icon is visible.
     */
    fun hasReplayControl(root: AccessibilityNodeInfo): Boolean {
        var found = false
        traverseNodes(root) { node ->
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""
            if (desc.contains("replay") || desc.contains("restart") ||
                desc.contains("play again") || desc.contains("watch again") ||
                text.contains("replay") || id.contains("replay") || id.contains("restart")
            ) {
                found = true
            }
        }
        return found
    }

    /**
     * Finds a visible and clickable "Next" or "Next Video" button if exposed.
     */
    fun findNextButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""

            val isNext = (desc == "next" || desc == "next video" || desc == "next item" ||
                          text == "next" || id.contains("btn_next") || id.contains("next_btn")) &&
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

    /**
     * Extracts an anonymous privacy-safe signature for the current media item.
     * Uses media title/filename or total duration and child order.
     */
    fun extractGenericMediaSignature(root: AccessibilityNodeInfo, prefix: String): String? {
        var titleSig: String? = null
        var totalDuration: Int? = null
        val contentDescSigs = mutableListOf<String>()

        traverseNodes(root) { node ->
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""

            if (text.endsWith(".mp4", true) || text.endsWith(".mkv", true) ||
                text.endsWith(".mov", true) || text.endsWith(".3gp", true) ||
                id.contains("title") || id.contains("filename")
            ) {
                if (text.isNotBlank() && titleSig == null) {
                    titleSig = "${prefix}_title_${text.hashCode()}"
                }
            }

            if (desc.contains("taken on", true) || desc.contains("video", true) ||
                desc.contains("photo", true) || desc.contains("202", true) ||
                desc.contains("AM", true) || desc.contains("PM", true)
            ) {
                if (desc.length > 5 && !desc.equals("video", true)) {
                    contentDescSigs.add(desc)
                }
            }
        }

        val times = extractTimeNodes(root)
        if (times.size >= 2) {
            totalDuration = times[1]
        }

        return when {
            titleSig != null && totalDuration != null -> "${titleSig}_dur_${totalDuration}"
            titleSig != null -> titleSig
            contentDescSigs.isNotEmpty() -> "${prefix}_desc_${contentDescSigs.joinToString().hashCode()}_dur_${totalDuration ?: 0}"
            totalDuration != null -> "${prefix}_dur_${totalDuration}"
            else -> null
        }
    }

    private inline fun traverseNodes(root: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Unit) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            action(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
    }
}
