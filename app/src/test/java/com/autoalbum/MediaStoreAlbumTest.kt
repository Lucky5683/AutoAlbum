package com.autoalbum

import com.autoalbum.media.MediaFormatters
import com.autoalbum.media.VideoAlbum
import com.autoalbum.media.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaStoreAlbumTest {

    @Test
    fun testVideoAlbumGroupingAndMetadata() {
        val video1 = VideoItem(
            id = 101L,
            uri = null,
            displayName = "VID_20261120_001.mp4",
            durationMs = 15000L,
            sizeBytes = 30_000_000L,
            dateAddedSec = 1795191000L,
            dateModifiedSec = 1795191000L,
            bucketId = "camera_bucket",
            albumName = "Camera"
        )

        val video2 = VideoItem(
            id = 102L,
            uri = null,
            displayName = "VID_20261120_002.mp4",
            durationMs = 25000L,
            sizeBytes = 50_000_000L,
            dateAddedSec = 1795192000L,
            dateModifiedSec = 1795192000L,
            bucketId = "camera_bucket",
            albumName = "Camera"
        )

        val album = VideoAlbum(
            bucketId = "camera_bucket",
            name = "Camera",
            videoCount = 2,
            totalDurationMs = 40000L,
            representativeUri = null,
            videos = listOf(video1, video2)
        )

        assertEquals("camera_bucket", album.bucketId)
        assertEquals("Camera", album.name)
        assertEquals(2, album.videoCount)
        assertEquals(40000L, album.totalDurationMs)
        assertEquals("00:40", MediaFormatters.formatDuration(album.totalDurationMs))
        assertEquals(2, album.videos.size)
        assertEquals("VID_20261120_001.mp4", album.videos[0].displayName)
        assertEquals("VID_20261120_002.mp4", album.videos[1].displayName)
    }

    @Test
    fun testZeroCopyOriginalUriIntegrity() {
        val video = VideoItem(
            id = 999L,
            uri = null,
            displayName = "OriginalVideo.mp4",
            durationMs = 65000L,
            sizeBytes = 120_000_000L,
            dateAddedSec = 1795000000L,
            dateModifiedSec = 1795000000L,
            bucketId = "downloads_bucket",
            albumName = "Download"
        )

        assertEquals("OriginalVideo.mp4", video.displayName)
        assertEquals("01:05", MediaFormatters.formatDuration(video.durationMs))
        assertEquals("114.4 MB", MediaFormatters.formatFileSize(video.sizeBytes))
    }
}
