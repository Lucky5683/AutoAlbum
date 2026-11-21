package com.autoalbum.media

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Storage Access Framework (SAF) Video Folder Manager.
 *
 * Guarantees:
 * - Uses standard Android Storage Access Framework (ACTION_OPEN_DOCUMENT_TREE).
 * - Scans original video files directly by their content URIs.
 * - ZERO file copying: files are never duplicated or transferred into AutoAlbum internal storage.
 * - ZERO modification: files are strictly read-only; no renaming, deleting or editing.
 * - ZERO broad storage permissions required (e.g., MANAGE_EXTERNAL_STORAGE not requested).
 * - ZERO internet access: manifest contains no android.permission.INTERNET.
 */
class SafFolderManager(private val context: Context) {

    companion object {
        private const val TAG = "AutoAlbum_SAF"
        private const val PREFS_NAME = "autoalbum_saf_prefs"
        private const val KEY_PERSISTED_TREE_URI = "persisted_tree_uri"

        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "mov", "3gp", "webm", "avi", "ts", "m4v", "wmv", "flv"
        )
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Persists the selected tree URI permission and saves it in SharedPreferences.
     */
    fun takeAndPersistFolderUri(treeUri: Uri): Boolean {
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
            prefs.edit().putString(KEY_PERSISTED_TREE_URI, treeUri.toString()).apply()
            Log.d(TAG, "[SAF] Persisted read permission for tree URI: $treeUri")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[SAF] Failed to take persistable URI permission: ${e.message}", e)
            false
        }
    }

    /**
     * Clears persisted folder.
     */
    fun clearPersistedFolder() {
        prefs.edit().remove(KEY_PERSISTED_TREE_URI).apply()
    }

    /**
     * Retrieves the previously persisted tree URI if valid and permission is retained.
     */
    fun getPersistedFolderUri(): Uri? {
        val uriStr = prefs.getString(KEY_PERSISTED_TREE_URI, null) ?: return null
        val uri = Uri.parse(uriStr)
        // Verify persistable permission is still granted
        val hasPermission = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        return if (hasPermission) uri else null
    }

    /**
     * Resolves a user-friendly folder display name for the selected tree URI.
     */
    fun getFolderDisplayName(treeUri: Uri): String {
        try {
            val docFile = DocumentFile.fromTreeUri(context, treeUri)
            val name = docFile?.name
            if (!name.isNullOrBlank()) {
                return name
            }
        } catch (e: Exception) {
            Log.w(TAG, "[SAF] Unable to get DocumentFile name: ${e.message}")
        }

        // Fallback: decode segment from tree document ID
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val colonIdx = docId.lastIndexOf(':')
            if (colonIdx != -1 && colonIdx < docId.length - 1) {
                docId.substring(colonIdx + 1).trimEnd('/')
            } else {
                docId
            }
        } catch (e: Exception) {
            treeUri.lastPathSegment ?: "Selected Folder"
        }
    }

    /**
     * Scans video items directly inside the selected tree folder.
     * Uses DocumentsContract child document query for performance, falling back to DocumentFile.
     * Does NOT copy any files or bytes into AutoAlbum.
     */
    suspend fun getVideosInFolder(treeUri: Uri): List<VideoItem> = withContext(Dispatchers.IO) {
        val videos = mutableListOf<VideoItem>()

        try {
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)

            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )

            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                var syntheticId = 1L
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idCol)
                    val displayName = cursor.getString(nameCol) ?: "Video_$syntheticId"
                    val mimeType = cursor.getString(mimeCol) ?: ""
                    val sizeBytes = if (sizeCol != -1 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L
                    val lastModifiedMs = if (modCol != -1 && !cursor.isNull(modCol)) cursor.getLong(modCol) else 0L

                    // Check if this document is a video
                    if (isVideoDocument(displayName, mimeType)) {
                        val fileDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        val durationMs = extractDurationHeader(fileDocUri)

                        videos.add(
                            VideoItem(
                                id = syntheticId++,
                                uri = fileDocUri,
                                displayName = displayName,
                                durationMs = durationMs,
                                sizeBytes = sizeBytes,
                                dateAddedSec = lastModifiedMs / 1000L,
                                dateModifiedSec = lastModifiedMs / 1000L,
                                bucketId = treeDocId,
                                albumName = getFolderDisplayName(treeUri)
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[SAF] DocumentsContract child query failed, falling back to DocumentFile: ${e.message}")
            videos.clear()
            videos.addAll(scanViaDocumentFile(treeUri))
        }

        // Sort videos by date modified descending (newest first) by default
        videos.sortByDescending { it.dateModifiedSec }
        Log.d(TAG, "[SAF] Scanned ${videos.size} videos from folder '$treeUri' without copying files.")
        videos
    }

    private fun scanViaDocumentFile(treeUri: Uri): List<VideoItem> {
        val result = mutableListOf<VideoItem>()
        val folder = DocumentFile.fromTreeUri(context, treeUri) ?: return result
        var syntheticId = 1L

        for (file in folder.listFiles()) {
            if (file.isFile && isVideoDocument(file.name ?: "", file.type ?: "")) {
                val fileUri = file.uri
                val durationMs = extractDurationHeader(fileUri)
                result.add(
                    VideoItem(
                        id = syntheticId++,
                        uri = fileUri,
                        displayName = file.name ?: "Video_$syntheticId",
                        durationMs = durationMs,
                        sizeBytes = file.length(),
                        dateAddedSec = file.lastModified() / 1000L,
                        dateModifiedSec = file.lastModified() / 1000L,
                        bucketId = treeUri.toString(),
                        albumName = folder.name ?: "Videos"
                    )
                )
            }
        }
        return result
    }

    private fun isVideoDocument(displayName: String, mimeType: String): Boolean {
        if (mimeType.startsWith("video/", ignoreCase = true)) {
            return true
        }
        val ext = displayName.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTENSIONS
    }

    /**
     * Extracts video duration from metadata header only, never reading or copying video bytes.
     */
    private fun extractDurationHeader(uri: Uri): Long {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try {
                retriever?.release()
            } catch (ignored: Exception) {}
        }
    }
}
