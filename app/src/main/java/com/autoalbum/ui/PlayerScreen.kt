@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.media3.common.util.UnstableApi::class
)

package com.autoalbum.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.autoalbum.media.MediaFormatters
import com.autoalbum.media.ThumbnailLoader
import com.autoalbum.media.VideoAlbum
import com.autoalbum.media.VideoItem
import com.autoalbum.player.AutoplayController
import com.autoalbum.player.AutoplayDirection
import com.autoalbum.ui.theme.*

/**
 * Finds the host Activity from a Compose Context safely.
 */
private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Fullscreen Gallery-Style Video Player Screen.
 *
 * Guarantees:
 * 1. Fullscreen video container filling 100% of the screen area (zero shift/jump when controls toggle).
 * 2. Immersive system UI (hides status/nav bars during playback; restores on exit).
 * 3. AspectRatioFrameLayout.RESIZE_MODE_FIT (zero distortion, supports portrait & landscape).
 * 4. Gallery-Style Horizontal Swipe Navigation:
 *    - Right-to-Left swipe: instantly opens next video.
 *    - Left-to-Right swipe: instantly opens previous video.
 *    - Accidental vertical/small drags filtered by distance threshold and axis dominance.
 *    - Boundary protection: clamps safely without crash or looping, with clear user feedback.
 *    - Independence: manual swipe preserves autoplay direction and highlighted arrow.
 * 5. Tap-to-Hide and Tap-to-Show Controls:
 *    - Tap on video area toggles controls visibility (tap-to-pause removed).
 *    - When hidden: controls row and top bar fade out; video plays uninterrupted in clean fullscreen.
 *    - When shown: controls and top bar fade in.
 *    - Central Play/Pause button is the exclusive control for toggling playback.
 * 6. Screen-awake behavior: FLAG_KEEP_SCREEN_ON active strictly while actively playing.
 */
@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    album: VideoAlbum,
    initialVideoIndex: Int,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }
    val window = activity?.window

    // Autoplay state controller
    val autoplayController = remember(album) {
        AutoplayController(
            videoCount = album.videos.size,
            initialIndex = initialVideoIndex,
            initialDirection = AutoplayDirection.NONE
        )
    }

    var currentIndex by remember { mutableStateOf(autoplayController.currentIndex) }
    var autoplayDirection by remember { mutableStateOf(autoplayController.direction) }
    var isEndOfList by remember { mutableStateOf(autoplayController.isEndOfList) }
    var endOfListMessage by remember { mutableStateOf(autoplayController.endOfListMessage) }

    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showQueueSheet by remember { mutableStateOf(false) }

    // Controls visibility toggle (tap video to hide/show)
    var controlsVisible by remember { mutableStateOf(true) }

    // Helper to update screen-awake flag strictly based on actual playback state
    val updateKeepScreenOn: (Boolean) -> Unit = remember(window) {
        { keepAwake ->
            if (window != null) {
                if (keepAwake) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    // Initialize ExoPlayer with AudioFocus handling
    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()
            setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            playWhenReady = true
        }
    }

    // Helper to load and play a video at a given index
    fun playVideo(index: Int, playImmediately: Boolean = true) {
        val video = album.videos.getOrNull(index) ?: return
        val uri = video.uri
        if (uri == null) {
            errorMessage = "Video URI is not available"
            return
        }
        errorMessage = null
        try {
            val mediaItem = MediaItem.fromUri(uri)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            if (playImmediately) {
                exoPlayer.play()
            } else {
                exoPlayer.pause()
            }
        } catch (e: Exception) {
            errorMessage = "Failed to load video: ${e.message}"
        }
    }

    // Manual Swipe Next handler (Right-to-Left swipe)
    val handleSwipeNext: () -> Unit = {
        if (currentIndex + 1 < album.videos.size) {
            val nextIndex = currentIndex + 1
            autoplayController.jumpToIndex(nextIndex)
            currentIndex = autoplayController.currentIndex
            isEndOfList = autoplayController.isEndOfList
            endOfListMessage = autoplayController.endOfListMessage
            playVideo(nextIndex, playImmediately = exoPlayer.isPlaying)
        } else {
            Toast.makeText(context, "Last video in album", Toast.LENGTH_SHORT).show()
        }
    }

    // Manual Swipe Previous handler (Left-to-Right swipe)
    val handleSwipePrev: () -> Unit = {
        if (currentIndex - 1 >= 0) {
            val prevIndex = currentIndex - 1
            autoplayController.jumpToIndex(prevIndex)
            currentIndex = autoplayController.currentIndex
            isEndOfList = autoplayController.isEndOfList
            endOfListMessage = autoplayController.endOfListMessage
            playVideo(prevIndex, playImmediately = exoPlayer.isPlaying)
        } else {
            Toast.makeText(context, "First video in album", Toast.LENGTH_SHORT).show()
        }
    }

    // Player event listener for state changes, completion, errors, and screen-awake sync
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        isBuffering = true
                    }
                    Player.STATE_READY -> {
                        isBuffering = false
                    }
                    Player.STATE_ENDED -> {
                        isBuffering = false
                        // Turn off screen-awake when ended
                        updateKeepScreenOn(false)

                        // Current video finished naturally: evaluate autoplay advancement
                        val nextIndex = autoplayController.onVideoCompleted()
                        currentIndex = autoplayController.currentIndex
                        isEndOfList = autoplayController.isEndOfList
                        endOfListMessage = autoplayController.endOfListMessage

                        if (nextIndex != null) {
                            playVideo(nextIndex, playImmediately = true)
                        } else {
                            // Autoplay inactive or reached boundary: stop and stay paused
                            exoPlayer.pause()
                            isPlaying = false
                        }
                    }
                    Player.STATE_IDLE -> {
                        isBuffering = false
                        updateKeepScreenOn(false)
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                // Keep screen awake ONLY while actively playing
                updateKeepScreenOn(playing)
            }

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                isPlaying = false
                updateKeepScreenOn(false)
                errorMessage = "Playback error: ${error.localizedMessage ?: "unsupported format"}"
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    // Load initial video
    LaunchedEffect(initialVideoIndex) {
        autoplayController.jumpToIndex(initialVideoIndex)
        currentIndex = autoplayController.currentIndex
        playVideo(currentIndex, playImmediately = true)
    }

    // Immersive fullscreen mode management:
    // Hide system bars when entering player; restore on exit
    DisposableEffect(window) {
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
                // Ensure screen-awake flag is cleanly removed on exit
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    // Lifecycle observer: pause on background, release on exit
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    exoPlayer.pause()
                    updateKeepScreenOn(false)
                }
                Lifecycle.Event.ON_RESUME -> {
                    // Re-apply immersive mode on resume
                    if (window != null) {
                        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                        insetsController.systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        insetsController.hide(WindowInsetsCompat.Type.systemBars())
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            updateKeepScreenOn(false)
            exoPlayer.release()
        }
    }

    // Handle back button (hardware or gesture) to cleanly exit player screen
    BackHandler {
        onBackClick()
    }

    val currentVideo = album.videos.getOrNull(currentIndex)

    // ROOT CONTAINER: Fullscreen Box ensuring video size NEVER shifts or jumps when controls toggle
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. FULLSCREEN VIDEO VIEW (Fills entire screen at all times)
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false // Controls are rendered in Jetpack Compose
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. COORDINATED GESTURE TOUCH LAYER
        // Handles:
        // - Single Tap: Toggle controls visibility (tap-to-pause completely removed).
        // - Horizontal Swipe Right-to-Left: Immediately open next video.
        // - Horizontal Swipe Left-to-Right: Immediately open previous video.
        // - Filters small accidental movements (< 40dp) and vertical scrolls.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(album, currentIndex) {
                    val minSwipeDistancePx = 40.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        val startPos = down.position
                        var totalDx = 0f
                        var totalDy = 0f
                        var hasDragged = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break

                            if (change.changedToUp()) {
                                change.consume()
                                if (!hasDragged) {
                                    // Single tap: toggle controls visibility
                                    controlsVisible = !controlsVisible
                                } else {
                                    // Swipe: check horizontal threshold and horizontal dominance
                                    if (kotlin.math.abs(totalDx) >= minSwipeDistancePx &&
                                        kotlin.math.abs(totalDx) > kotlin.math.abs(totalDy) * 1.2f
                                    ) {
                                        if (totalDx < 0) {
                                            // Right-to-Left -> Next video
                                            handleSwipeNext()
                                        } else {
                                            // Left-to-Right -> Previous video
                                            handleSwipePrev()
                                        }
                                    }
                                }
                                break
                            }

                            if (change.isConsumed) {
                                break
                            }

                            val currentPos = change.position
                            val dx = currentPos.x - startPos.x
                            val dy = currentPos.y - startPos.y
                            totalDx = dx
                            totalDy = dy

                            if (kotlin.math.hypot(dx.toDouble(), dy.toDouble()) > viewConfiguration.touchSlop) {
                                hasDragged = true
                            }
                        }
                    }
                }
        )

        // 3. BUFFERING INDICATOR
        if (isBuffering) {
            CircularProgressIndicator(
                color = PrimaryCyan,
                strokeWidth = 3.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(44.dp)
            )
        }

        // 4. TOP BAR OVERLAY (Title, Video Index, Back and Queue buttons)
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xCC000000),
                                Color(0x66000000),
                                Color.Transparent
                            )
                        )
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onBackClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0x801A2130),
                        contentColor = PrimaryCyan
                    ),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(38.dp)
                ) {
                    Text(
                        text = "←",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentVideo?.displayName ?: album.name,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${album.name} • Video ${currentIndex + 1} of ${album.videos.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }

                Button(
                    onClick = { showQueueSheet = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0x801A2130),
                        contentColor = PrimaryCyan
                    ),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(38.dp)
                ) {
                    Text(
                        text = "☰",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 5. COMPACT BOTTOM PLAYBACK CONTROLS
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0xCC131722),
                                DarkSurface
                            )
                        )
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* Absorb clicks on control background */ }
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // LEFT ARROW (Autoplay Previous)
                    val isLeftSelected = autoplayDirection == AutoplayDirection.BACKWARD
                    Button(
                        onClick = {
                            autoplayController.selectBackward()
                            autoplayDirection = autoplayController.direction
                            isEndOfList = autoplayController.isEndOfList
                            endOfListMessage = autoplayController.endOfListMessage
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isLeftSelected) PrimaryCyan else DarkSurfaceElevated,
                            contentColor = if (isLeftSelected) OnPrimaryDark else TextSecondary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .height(38.dp)
                            .weight(1f)
                            .then(
                                if (isLeftSelected) {
                                    Modifier.border(2.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(10.dp))
                                } else {
                                    Modifier
                                }
                            ),
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text(
                            text = "◀ Autoplay Prev",
                            fontSize = 13.sp,
                            fontWeight = if (isLeftSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // CENTRAL PLAY / PAUSE BUTTON (Exclusive Play/Pause toggle)
                    Button(
                        onClick = {
                            if (exoPlayer.isPlaying) {
                                exoPlayer.pause()
                            } else {
                                if (exoPlayer.playbackState == Player.STATE_ENDED) {
                                    exoPlayer.seekTo(0)
                                }
                                exoPlayer.play()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryCyanVariant,
                            contentColor = Color.White
                        ),
                        shape = CircleShape,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Text(
                            text = if (isPlaying) "⏸" else "▶",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // RIGHT ARROW (Autoplay Next)
                    val isRightSelected = autoplayDirection == AutoplayDirection.FORWARD
                    Button(
                        onClick = {
                            autoplayController.selectForward()
                            autoplayDirection = autoplayController.direction
                            isEndOfList = autoplayController.isEndOfList
                            endOfListMessage = autoplayController.endOfListMessage
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRightSelected) PrimaryCyan else DarkSurfaceElevated,
                            contentColor = if (isRightSelected) OnPrimaryDark else TextSecondary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .height(38.dp)
                            .weight(1f)
                            .then(
                                if (isRightSelected) {
                                    Modifier.border(2.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(10.dp))
                                } else {
                                    Modifier
                                }
                            ),
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text(
                            text = "Autoplay Next ▶",
                            fontSize = 13.sp,
                            fontWeight = if (isRightSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }

                // Compact status text
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when {
                        isEndOfList && endOfListMessage != null -> "⏹ $endOfListMessage"
                        autoplayDirection == AutoplayDirection.FORWARD -> "▶ Autoplay Next on completion"
                        autoplayDirection == AutoplayDirection.BACKWARD -> "◀ Autoplay Prev on completion"
                        else -> "Autoplay inactive (Select ◀ or ▶)"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = when {
                        isEndOfList -> StatusIdleAmber
                        autoplayDirection != AutoplayDirection.NONE -> PrimaryCyan
                        else -> TextTertiary
                    },
                    textAlign = TextAlign.Center
                )
            }
        }

        // 6. END-OF-LIST BANNER OVERLAY
        if (isEndOfList && endOfListMessage != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xEE131722)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "⏹ $endOfListMessage",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = StatusIdleAmber,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Autoplay stopped at album boundary (no auto-loop)",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // 7. ERROR MESSAGE OVERLAY
        if (errorMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC111827))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = errorMessage ?: "Playback error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = StatusStoppedRed,
                    textAlign = TextAlign.Center
                )
            }
        }

        // 8. OPTIONAL QUEUE MODAL BOTTOM SHEET
        if (showQueueSheet) {
            ModalBottomSheet(
                onDismissRequest = { showQueueSheet = false },
                containerColor = DarkSurface,
                scrimColor = Color.Black.copy(alpha = 0.6f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Album Queue (${album.videos.size} videos)",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.6f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        itemsIndexed(album.videos, key = { _, v -> v.id }) { index, video ->
                            val isCurrent = index == currentIndex
                            PlayerQueueItem(
                                video = video,
                                isCurrent = isCurrent,
                                index = index + 1,
                                onClick = {
                                    if (index != currentIndex) {
                                        autoplayController.jumpToIndex(index)
                                        currentIndex = autoplayController.currentIndex
                                        isEndOfList = autoplayController.isEndOfList
                                        endOfListMessage = autoplayController.endOfListMessage
                                        playVideo(index, playImmediately = exoPlayer.isPlaying)
                                    }
                                    showQueueSheet = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerQueueItem(
    video: VideoItem,
    isCurrent: Boolean,
    index: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var thumbnailBitmap by remember(video.uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(video.uri) {
        thumbnailBitmap = ThumbnailLoader.loadThumbnail(context, video.uri, 160, 120)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .then(
                if (isCurrent) {
                    Modifier.border(1.5.dp, PrimaryCyan, RoundedCornerShape(10.dp))
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) DarkSurfaceElevated else DarkSurface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail with index badge
            Box(
                modifier = Modifier
                    .size(width = 72.dp, height = 48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnailBitmap != null) {
                    Image(
                        bitmap = thumbnailBitmap!!.asImageBitmap(),
                        contentDescription = video.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(text = "▶", fontSize = 16.sp, color = PrimaryCyan)
                }

                if (isCurrent) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x55000000)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "▶ Now",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            ),
                            color = PrimaryCyan
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$index. ${video.displayName}",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                    ),
                    color = if (isCurrent) PrimaryCyan else TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${MediaFormatters.formatDuration(video.durationMs)} • ${MediaFormatters.formatFileSize(video.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }
    }
}
