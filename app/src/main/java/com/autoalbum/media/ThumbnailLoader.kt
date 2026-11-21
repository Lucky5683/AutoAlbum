package com.autoalbum.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lightweight, in-memory thumbnail loader for MediaStore video URIs.
 *
 * Guarantees:
 * - Uses native Android 10+ ContentResolver.loadThumbnail().
 * - In-memory LRU caching to prevent redundant I/O.
 * - ZERO file copies: never writes thumbnails or videos to disk.
 * - Operates strictly on original content URIs.
 */
object ThumbnailLoader {
    private const val TAG = "ThumbnailLoader"

    // In-memory cache for up to 60 bitmaps
    private val memoryCache: LruCache<String, Bitmap> by lazy {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = (maxMemory / 8).coerceIn(1024, 20480) // 1/8th of memory, min 1MB, max 20MB
        object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }
    }

    suspend fun loadThumbnail(context: Context, uri: Uri?, width: Int = 240, height: Int = 240): Bitmap? = withContext(Dispatchers.IO) {
        if (uri == null) return@withContext null
        val cacheKey = "${uri}_${width}x${height}"

        memoryCache.get(cacheKey)?.let {
            return@withContext it
        }

        val bitmap: Bitmap? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(width, height), null)
            } else {
                extractFrameFallback(context, uri)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Native thumbnail load failed for $uri, falling back: ${e.message}")
            extractFrameFallback(context, uri)
        }

        if (bitmap != null) {
            memoryCache.put(cacheKey, bitmap)
        }

        bitmap
    }

    private fun extractFrameFallback(context: Context, uri: Uri): Bitmap? {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever?.release()
            } catch (ignored: Exception) {}
        }
    }
}
