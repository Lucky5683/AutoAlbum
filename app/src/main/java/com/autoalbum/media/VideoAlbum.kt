package com.autoalbum.media

import android.net.Uri

/**
 * Represents a collection/folder of local videos grouped by parent folder (bucket).
 */
data class VideoAlbum(
    val bucketId: String,
    val name: String,
    val videoCount: Int,
    val totalDurationMs: Long,
    val representativeUri: Uri?,
    val videos: List<VideoItem>
)

