package com.autoalbum.media

import android.net.Uri

/**
 * Model representing a single local video item from SAF folder picker or MediaStore.
 */
data class VideoItem(
    val id: Long,
    val uri: Uri?,
    val displayName: String,
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val dateAddedSec: Long = 0L,
    val dateModifiedSec: Long = 0L,
    val bucketId: String = "",
    val albumName: String = ""
)
