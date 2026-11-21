package com.autoalbum.media

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android MediaStore Video Album Manager.
 *
 * Guarantees:
 * - Queries existing local videos via Android MediaStore.
 * - Groups videos into album collections by MediaStore bucket ID & display name.
 * - Skips photographs completely (queries MediaStore.Video.Media exclusively).
 * - ZERO file copies: never copies videos or writes to storage.
 * - ZERO new folders created on disk.
 * - ZERO file modifications or deletions.
 * - Uses only the required video-read permission.
 */
class MediaManager(private val context: Context) {

    companion object {
        private const val TAG = "AutoAlbum_Media"
    }

    /**
     * Lists local video collections grouped by their parent album/folder (bucketId).
     * Photographs are completely skipped as this queries MediaStore.Video.Media exclusively.
     */
    suspend fun getLocalVideoAlbums(): List<VideoAlbum> = withContext(Dispatchers.IO) {
        val videos = getLocalVideos()
        val grouped = videos.groupBy { it.bucketId }

        grouped.map { (bucketId, albumVideos) ->
            val sortedVideos = albumVideos.sortedByDescending { it.dateModifiedSec }
            val firstVideo = sortedVideos.firstOrNull()
            val albumName = firstVideo?.albumName?.takeIf { it.isNotBlank() } ?: "Videos"
            val totalDuration = sortedVideos.sumOf { it.durationMs }
            VideoAlbum(
                bucketId = bucketId,
                name = albumName,
                videoCount = sortedVideos.size,
                totalDurationMs = totalDuration,
                representativeUri = firstVideo?.uri,
                videos = sortedVideos
            )
        }.sortedByDescending { it.videoCount }
    }

    /**
     * Queries MediaStore for local videos.
     * Photographs are strictly omitted via collection URI and MIME-type constraints.
     */
    suspend fun getLocalVideos(): List<VideoItem> = withContext(Dispatchers.IO) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        )

        // Ensure non-video MIME types (photos, audio) are excluded
        val selection = "${MediaStore.Video.Media.MIME_TYPE} LIKE ?"
        val selectionArgs = arrayOf("video/%")
        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"

        val result = mutableListOf<VideoItem>()

        try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val bucketIdCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_ID)
                val bucketNameCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val displayName = cursor.getString(nameCol) ?: "Video $id"
                    val duration = cursor.getLong(durationCol)
                    val size = cursor.getLong(sizeCol)
                    val dateAdded = cursor.getLong(dateAddedCol)
                    val dateModified = cursor.getLong(dateModifiedCol)
                    val bucketId = if (bucketIdCol != -1) cursor.getString(bucketIdCol) ?: "default" else "default"
                    val albumName = if (bucketNameCol != -1) cursor.getString(bucketNameCol) ?: "Videos" else "Videos"

                    result += VideoItem(
                        id = id,
                        uri = uri,
                        displayName = displayName,
                        durationMs = duration,
                        sizeBytes = size,
                        dateAddedSec = dateAdded,
                        dateModifiedSec = dateModified,
                        bucketId = bucketId,
                        albumName = albumName
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MediaStore videos: ${e.message}", e)
        }

        Log.d(TAG, "Queried ${result.size} videos from MediaStore.")
        result
    }
}
