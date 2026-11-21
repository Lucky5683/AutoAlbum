package com.autoalbum.player

/**
 * Autoplay direction selection.
 * - NONE: Autoplay is inactive.
 * - FORWARD: After the current video ends, advance to the next video in the album.
 * - BACKWARD: After the current video ends, advance to the previous video in the album.
 */
enum class AutoplayDirection {
    NONE,
    FORWARD,
    BACKWARD
}

/**
 * Encapsulates direction-based autoplay state and sequential navigation rules.
 *
 * Guarantees:
 * 1. Only one direction can be active at a time.
 * 2. Selecting a direction while playing does NOT skip or seek the current video.
 * 3. Selecting a direction when paused does NOT resume playback.
 * 4. The selected direction remains highlighted continuously until explicitly changed.
 * 5. Advancing happens ONLY when the current video completes (Player.STATE_ENDED).
 * 6. At either end of the list, stops playback and reports end-of-list (zero automatic looping).
 * 7. Inactive when AutoplayDirection.NONE.
 */
class AutoplayController(
    val videoCount: Int,
    initialIndex: Int = 0,
    initialDirection: AutoplayDirection = AutoplayDirection.NONE
) {
    var currentIndex: Int = initialIndex.coerceIn(0, (videoCount - 1).coerceAtLeast(0))
        private set

    var direction: AutoplayDirection = initialDirection
        private set

    var isEndOfList: Boolean = false
        private set

    var endOfListMessage: String? = null
        private set

    /**
     * Toggles or selects the forward autoplay direction (Right arrow).
     * If already FORWARD, keeps it or toggles.
     */
    fun selectForward() {
        direction = if (direction == AutoplayDirection.FORWARD) {
            AutoplayDirection.NONE
        } else {
            AutoplayDirection.FORWARD
        }
        isEndOfList = false
        endOfListMessage = null
    }

    /**
     * Toggles or selects the backward autoplay direction (Left arrow).
     * If already BACKWARD, keeps it or toggles.
     */
    fun selectBackward() {
        direction = if (direction == AutoplayDirection.BACKWARD) {
            AutoplayDirection.NONE
        } else {
            AutoplayDirection.BACKWARD
        }
        isEndOfList = false
        endOfListMessage = null
    }

    /**
     * Evaluates navigation when the current video reaches completion.
     *
     * @return The next video index to play, or null if autoplay is inactive or an end is reached.
     */
    fun onVideoCompleted(): Int? {
        when (direction) {
            AutoplayDirection.FORWARD -> {
                if (currentIndex + 1 < videoCount) {
                    currentIndex += 1
                    isEndOfList = false
                    endOfListMessage = null
                    return currentIndex
                } else {
                    isEndOfList = true
                    endOfListMessage = "Reached end of album (last video)"
                    return null
                }
            }
            AutoplayDirection.BACKWARD -> {
                if (currentIndex - 1 >= 0) {
                    currentIndex -= 1
                    isEndOfList = false
                    endOfListMessage = null
                    return currentIndex
                } else {
                    isEndOfList = true
                    endOfListMessage = "Reached start of album (first video)"
                    return null
                }
            }
            AutoplayDirection.NONE -> {
                isEndOfList = false
                endOfListMessage = null
                return null
            }
        }
    }

    /**
     * Manually jumps to a specific video in the album list.
     */
    fun jumpToIndex(index: Int) {
        if (index in 0 until videoCount) {
            currentIndex = index
            isEndOfList = false
            endOfListMessage = null
        }
    }
}
