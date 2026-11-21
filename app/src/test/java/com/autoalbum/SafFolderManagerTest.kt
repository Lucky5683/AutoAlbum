package com.autoalbum

import com.autoalbum.media.MediaFormatters
import com.autoalbum.media.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Test

class SafFolderManagerTest {

    @Test
    fun testVideoItemPreservesMetadata() {
        val item = VideoItem(
            id = 1L,
            uri = null,
            displayName = "VID_20261120.mp4",
            durationMs = 14500L,
            sizeBytes = 25_000_000L,
            dateAddedSec = 1795190000L,
            dateModifiedSec = 1795190000L,
            bucketId = "primary:DCIM/Camera",
            albumName = "Camera"
        )

        assertEquals("VID_20261120.mp4", item.displayName)
        assertEquals(14500L, item.durationMs)
        assertEquals(25_000_000L, item.sizeBytes)
        assertEquals("00:14", MediaFormatters.formatDuration(item.durationMs))
        assertEquals("23.8 MB", MediaFormatters.formatFileSize(item.sizeBytes))
    }

    @Test
    fun testMediaFormattersDuration() {
        assertEquals("00:00", MediaFormatters.formatDuration(0L))
        assertEquals("00:05", MediaFormatters.formatDuration(5000L))
        assertEquals("01:30", MediaFormatters.formatDuration(90000L))
        assertEquals("1:00:00", MediaFormatters.formatDuration(3600000L))
        assertEquals("1:05:23", MediaFormatters.formatDuration(3923000L))
    }

    @Test
    fun testMediaFormattersFileSize() {
        assertEquals("0 B", MediaFormatters.formatFileSize(0L))
        assertEquals("500 B", MediaFormatters.formatFileSize(500L))
        assertEquals("1.5 KB", MediaFormatters.formatFileSize(1536L))
        assertEquals("10.0 MB", MediaFormatters.formatFileSize(10 * 1024 * 1024L))
        assertEquals("1.5 GB", MediaFormatters.formatFileSize((1.5 * 1024 * 1024 * 1024).toLong()))
    }
}
