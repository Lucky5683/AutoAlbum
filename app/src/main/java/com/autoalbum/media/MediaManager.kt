package com.autoalbum.media

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaManager(private val context: Context) {

    /**
     * Lists local video collections grouped by their parent album/folder (bucketId).
     * Photographs are completely skipped as this queries MediaStore.Video.Media exclusively.
     */
    suspend fun getLocalVideoAlbums(): List<VideoAlbum> = withContext(Dispatchers.IO) {
        val videos = getLocalVideos()
        val grouped = videos.groupBy { it.bucketId }

        grouped.map { (bucketId, albumVideos) ->
            val firstVideo = albumVideos.firstOrNull()
            val albumName = firstVideo?.albumName?.takeIf { it.isNotBlank() } ?: "Internal Videos"
            val totalDuration = albumVideos.sumOf { it.durationMs }
            VideoAlbum(
                bucketId = bucketId,
                name = albumName,
                videoCount = albumVideos.size,
                totalDurationMs = totalDuration,
                representativeUri = firstVideo?.uri,
                videos = albumVideos
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
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

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
            // Gracefully catch security/cursor exceptions if permission is ungranted or revoked
        }

        result
    }

    /**
     * Resolves metadata for user-selected videos from the zero-permission Android Photo Picker.
     */
    suspend fun resolvePickedVideoUris(uris: List<Uri>): List<VideoItem> = withContext(Dispatchers.IO) {
        uris.mapIndexedNotNull { index, uri ->
            try {
                var displayName = "Selected Video ${index + 1}"
                var size = 0L

                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1) displayName = cursor.getString(nameIndex) ?: displayName
                        if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                    }
                }

                var duration = 0L
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, uri)
                    val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    duration = durStr?.toLongOrNull() ?: 0L
                    retriever.release()
                } catch (e: Exception) {
                    // Ignore metadata retriever error if unsupported
                }

                val nowSec = System.currentTimeMillis() / 1000

                VideoItem(
                    id = uri.hashCode().toLong(),
                    uri = uri,
                    displayName = displayName,
                    durationMs = duration,
                    sizeBytes = size,
                    dateAddedSec = nowSec,
                    dateModifiedSec = nowSec,
                    bucketId = "photo_picker",
                    albumName = "Selected Videos"
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
