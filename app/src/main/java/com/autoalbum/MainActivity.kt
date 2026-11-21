package com.autoalbum

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.autoalbum.media.MediaManager
import com.autoalbum.media.VideoAlbum
import com.autoalbum.ui.HomeScreen
import com.autoalbum.ui.theme.AutoAlbumTheme
import kotlinx.coroutines.launch

/**
 * AutoAlbum Main Activity.
 *
 * Implements a native, local gallery-like album browser using Android MediaStore.
 *
 * Guarantees:
 * 1. Uses MediaStore to query existing local videos grouped into bucket albums.
 * 2. Displays existing albums directly inside AutoAlbum.
 * 3. Shows album thumbnail, album name, and video count.
 * 4. Tapping an album opens and displays the videos inside that album.
 * 5. Tapping a video opens the original URI via ACTION_VIEW in the system player.
 * 6. ZERO video copying into AutoAlbum.
 * 7. ZERO new folder creation on disk.
 * 8. ZERO modification, deletion, or renaming of user videos or albums.
 * 9. Uses only the required video-read permission (READ_MEDIA_VIDEO on Android 13+).
 * 10. ZERO INTERNET permission.
 * 11. ZERO floating icons or Accessibility automation.
 */
class MainActivity : ComponentActivity() {

    private lateinit var mediaManager: MediaManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        mediaManager = MediaManager(applicationContext)

        setContent {
            AutoAlbumTheme {
                val coroutineScope = rememberCoroutineScope()
                var albums by remember { mutableStateOf<List<VideoAlbum>>(emptyList()) }
                var selectedAlbum by remember { mutableStateOf<VideoAlbum?>(null) }
                var selectedVideoIndex by remember { mutableStateOf<Int?>(null) }
                var isLoading by remember { mutableStateOf(false) }

                val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_VIDEO
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }

                var hasPermission by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            requiredPermission
                        ) == PackageManager.PERMISSION_GRANTED
                    )
                }

                fun loadAlbums() {
                    coroutineScope.launch {
                        isLoading = true
                        try {
                            val loaded = mediaManager.getLocalVideoAlbums()
                            albums = loaded
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Failed to load albums: ${e.message}", Toast.LENGTH_SHORT).show()
                        } finally {
                            isLoading = false
                        }
                    }
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    hasPermission = isGranted
                    if (isGranted) {
                        loadAlbums()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Video permission is required to view albums",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                LaunchedEffect(hasPermission) {
                    if (hasPermission) {
                        loadAlbums()
                    }
                }

                HomeScreen(
                    albums = albums,
                    selectedAlbum = selectedAlbum,
                    selectedVideoIndex = selectedVideoIndex,
                    isLoading = isLoading,
                    hasPermission = hasPermission,
                    onRequestPermission = {
                        permissionLauncher.launch(requiredPermission)
                    },
                    onAlbumClick = { album ->
                        selectedAlbum = album
                        selectedVideoIndex = null
                    },
                    onBackToAlbums = {
                        selectedAlbum = null
                        selectedVideoIndex = null
                    },
                    onVideoClick = { index ->
                        selectedVideoIndex = index
                    },
                    onBackFromPlayer = {
                        selectedVideoIndex = null
                    }
                )
            }
        }
    }
}
