package com.autoalbum.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoalbum.media.MediaFormatters
import com.autoalbum.media.ThumbnailLoader
import com.autoalbum.media.VideoAlbum
import com.autoalbum.media.VideoItem
import com.autoalbum.ui.theme.*

/**
 * AutoAlbum Gallery Browser UI.
 *
 * Implements:
 * 1. Album grid browsing directly inside AutoAlbum.
 * 2. Album thumbnail, album name, and video count.
 * 3. Video list when an album is selected.
 * 4. Tap video to open/play original URI in system player.
 * 5. Clean, low-power, modern dark theme.
 * 6. ZERO floating icons, ZERO file copying, ZERO folder creation.
 */
@Composable
fun HomeScreen(
    albums: List<VideoAlbum>,
    selectedAlbum: VideoAlbum?,
    selectedVideoIndex: Int?,
    isLoading: Boolean,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onAlbumClick: (VideoAlbum) -> Unit,
    onBackToAlbums: () -> Unit,
    onVideoClick: (Int) -> Unit,
    onBackFromPlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Intercept back button when inside an album (and not in player) to return to album grid
    BackHandler(enabled = selectedAlbum != null && selectedVideoIndex == null) {
        onBackToAlbums()
    }

    if (selectedAlbum != null && selectedVideoIndex != null) {
        // True Edge-to-Edge Fullscreen Player
        PlayerScreen(
            album = selectedAlbum,
            initialVideoIndex = selectedVideoIndex,
            onBackClick = onBackFromPlayer,
            modifier = modifier.fillMaxSize()
        )
    } else {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = DarkBackground
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when {
                    !hasPermission -> {
                        PermissionRequestScreen(
                            onRequestPermission = onRequestPermission,
                            modifier = Modifier.padding(24.dp)
                        )
                    }

                    isLoading -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                color = PrimaryCyan,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Loading video albums...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }

                    selectedAlbum != null -> {
                        // Inside an Album: Display video list
                        AlbumDetailScreen(
                            album = selectedAlbum,
                            onBackClick = onBackToAlbums,
                            onVideoClick = { video ->
                                val index = selectedAlbum.videos.indexOf(video)
                                if (index >= 0) onVideoClick(index)
                            }
                        )
                    }

                albums.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "No Video Albums Found",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "There are no local videos in your device's media store.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }

                else -> {
                    // Main Screen: Display Albums Grid
                    AlbumGridScreen(
                        albums = albums,
                        onAlbumClick = onAlbumClick
                    )
                }
            }
        }
    }
}
}

@Composable
private fun AlbumGridScreen(
    albums: List<VideoAlbum>,
    onAlbumClick: (VideoAlbum) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // App Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "AutoAlbum",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    ),
                    color = TextPrimary
                )
                Text(
                    text = "Gallery Video Albums (${albums.size})",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 20.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(albums, key = { it.bucketId }) { album ->
                AlbumCard(
                    album = album,
                    onClick = { onAlbumClick(album) }
                )
            }
        }
    }
}

@Composable
private fun AlbumCard(
    album: VideoAlbum,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var thumbnailBitmap by remember(album.representativeUri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(album.representativeUri) {
        thumbnailBitmap = ThumbnailLoader.loadThumbnail(context, album.representativeUri, 320, 240)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Thumbnail container with video count badge
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.25f)
                    .background(DarkSurfaceElevated),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnailBitmap != null) {
                    Image(
                        bitmap = thumbnailBitmap!!.asImageBitmap(),
                        contentDescription = "Thumbnail for ${album.name}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = "▶",
                        fontSize = 32.sp,
                        color = PrimaryCyan
                    )
                }

                // Video count badge in bottom-right corner of thumbnail
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xCC0A0C10))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${album.videoCount} ${if (album.videoCount == 1) "video" else "videos"}",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                }
            }

            // Album Name and metadata
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = MediaFormatters.formatDuration(album.totalDurationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun AlbumDetailScreen(
    album: VideoAlbum,
    onBackClick: () -> Unit,
    onVideoClick: (VideoItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Top Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onBackClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = DarkSurfaceElevated,
                    contentColor = PrimaryCyan
                ),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(42.dp)
            ) {
                Text(
                    text = "←",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column {
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${album.videoCount} videos • ${MediaFormatters.formatDuration(album.totalDurationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Video Items List
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            items(album.videos, key = { it.id }) { video ->
                VideoCardItem(
                    video = video,
                    onClick = { onVideoClick(video) }
                )
            }
        }
    }
}

@Composable
private fun VideoCardItem(
    video: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var thumbnailBitmap by remember(video.uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(video.uri) {
        thumbnailBitmap = ThumbnailLoader.loadThumbnail(context, video.uri, 200, 150)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail container with duration badge
            Box(
                modifier = Modifier
                    .size(width = 100.dp, height = 70.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkSurfaceElevated),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnailBitmap != null) {
                    Image(
                        bitmap = thumbnailBitmap!!.asImageBitmap(),
                        contentDescription = "Thumbnail for ${video.displayName}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(text = "▶", fontSize = 20.sp, color = PrimaryCyan)
                }

                // Duration badge on bottom
                if (video.durationMs > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xCC0A0C10))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = MediaFormatters.formatDuration(video.durationMs),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = TextPrimary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Metadata Column
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = MediaFormatters.formatFileSize(video.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )

                    if (video.dateModifiedSec > 0) {
                        Text(
                            text = " • ",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary
                        )
                        Text(
                            text = MediaFormatters.formatDate(video.dateModifiedSec),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRequestScreen(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(DarkSurfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "🎬", fontSize = 32.sp)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Video Access Required",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "AutoAlbum needs permission to read existing video albums on your device. Original videos remain untouched in place.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 16.dp),
            lineHeight = 22.sp
        )

        Spacer(modifier = Modifier.height(28.dp))

        Button(
            onClick = onRequestPermission,
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = PrimaryCyan,
                contentColor = OnPrimaryDark
            )
        ) {
            Text(
                text = "Grant Video Access",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}
