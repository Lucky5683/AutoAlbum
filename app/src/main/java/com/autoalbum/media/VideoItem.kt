package com.autoalbum.media

import android.net.Uri

/**
 * Model representing a single local video item from MediaStore or Photo Picker.
 */
data class VideoItem(
    val id: Long,
    val uri: Uri?,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
    val bucketId: String,
    val albumName: String
)
