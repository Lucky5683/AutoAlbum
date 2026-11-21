package com.autoalbum.automation.gallery

/**
 * Handles completion, loop wrap-around, elapsed playback tracking,
 * and latching heuristics for Google Photos video playback.
 * Extends [UniversalPlaybackDetector] for specialized Google Photos compatibility.
 */
class GooglePhotosLoopDetector : UniversalPlaybackDetector()
