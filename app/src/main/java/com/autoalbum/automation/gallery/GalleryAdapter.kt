package com.autoalbum.automation.gallery

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Modular gallery compatibility interface.
 * Isolates gallery-specific heuristics so AutoAlbum never assumes identical interfaces across gallery apps.
 */
interface GalleryAdapter {
    val packageName: String
    val appName: String

    fun isSupported(pkg: String): Boolean = pkg == packageName

    /** Returns true if a video player interface or video surface is active on screen. */
    fun isPlayerActive(rootNode: AccessibilityNodeInfo): Boolean

    /** Returns true if the displayed item is definitely a photo (not a video with hidden controls). */
    fun isPhoto(rootNode: AccessibilityNodeInfo): Boolean

    /** Returns true if video is currently playing. */
    fun isVideoPlaying(rootNode: AccessibilityNodeInfo): Boolean

    /** Returns true if the user manually paused the video. */
    fun isVideoPaused(rootNode: AccessibilityNodeInfo): Boolean

    /** Returns true if the video reached completion or replay indicator is shown. */
    fun isVideoCompleted(rootNode: AccessibilityNodeInfo): Boolean

    /** Finds a visible "Next" or "Next Video" clickable control if provided by the gallery UI. */
    fun findNextButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo?

    /** Attempts gallery-specific next action (e.g. ViewPager forward scroll). Returns true if dispatched. */
    fun performNextAction(rootNode: AccessibilityNodeInfo): Boolean = false

    /** Returns a privacy-safe unique signature for the active item. */
    fun getActiveItemSignature(rootNode: AccessibilityNodeInfo): String? = null

    /** Returns true if controls need a one-time gentle awakening to expose seekbar/duration. */
    fun needsControlAwakening(): Boolean = false

    /** Records that controls were awakened for the current item. */
    fun onControlsAwakened() {}

    /** Called when post-navigation verification determines item did not change. */
    fun onNavigationFailed() {}

    /** Resets session tracking on automation stop. */
    fun resetSession() {}
}
