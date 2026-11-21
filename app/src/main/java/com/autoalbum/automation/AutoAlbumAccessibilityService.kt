package com.autoalbum.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.autoalbum.automation.gallery.GalleryAdapter
import com.autoalbum.automation.gallery.GalleryRegistry

/**
 * Accessibility service for gallery video playback automation.
 *
 * Requirements & Diagnostics:
 * - Logs explicit diagnostics for connection, active gallery, player detection,
 *   completion detection, navigation action, and verification results.
 * - Do not issue repeated gestures when player state cannot be confirmed.
 * - Revalidates environment immediately prior to every action or gesture.
 * - Stops automation cleanly after repeated unchanged verification failures.
 */
class AutoAlbumAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoAlbum"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isPerformingNavigation = false
    private var isAdvanceScheduled = false
    private var lastNavigationTimestamp = 0L
    private var consecutiveNavigationFailures = 0

    // Periodic heartbeat to monitor playback state even when SurfaceView does not fire View events
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (AutomationCoordinator.state.value == AutomationCoordinator.State.RUNNING) {
                checkPeriodicPlaybackState()
                handler.postDelayed(this, 500)
            }
        }
    }

    private val coordinatorListener: (AutomationCoordinator.State) -> Unit = { state ->
        Log.d(TAG, "[AutomationState] State listener received state=$state")
        if (state == AutomationCoordinator.State.RUNNING) {
            handler.removeCallbacks(heartbeatRunnable)
            handler.post(heartbeatRunnable)
        } else {
            Log.d(TAG, "[Security] Automation state is $state. Cancelling pending navigation and heartbeat.")
            cancelPendingAdvance()
            handler.removeCallbacks(heartbeatRunnable)
            isPerformingNavigation = false
            if (state == AutomationCoordinator.State.STOPPED) {
                GalleryRegistry.resetAllSessions()
            }
        }
    }

    private val advanceRunnable = Runnable {
        performNavigation()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "[AccessibilityConnection] Service connected and initialized. Ready for gallery events.")
        AutomationCoordinator.addListener(coordinatorListener)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val currentState = AutomationCoordinator.state.value
        val packageName = event.packageName?.toString()

        // 1. Mandatory cancel check: non-gallery app switch or state not RUNNING
        if (currentState != AutomationCoordinator.State.RUNNING) {
            cancelPendingAdvance()
            isPerformingNavigation = false
            return
        }

        if (packageName != null && !GalleryRegistry.isSupportedPackage(packageName)) {
            Log.d(TAG, "[Security] Non-gallery package ($packageName) active. Cancelling pending navigation.")
            cancelPendingAdvance()
            isPerformingNavigation = false
            return
        }

        if (packageName == null) return

        // 2. Strict isolation: strictly ignore any package not in the gallery registry
        val adapter = GalleryRegistry.findAdapter(packageName) ?: run {
            cancelPendingAdvance()
            isPerformingNavigation = false
            return
        }

        // 3. Avoid overlapping navigation attempts
        if (isPerformingNavigation) return
        val now = System.currentTimeMillis()
        if (now - lastNavigationTimestamp < 1500) return

        val rootNode = rootInActiveWindow ?: return
        val rootPkg = rootNode.packageName?.toString()
        if (rootPkg != packageName || !GalleryRegistry.isSupportedPackage(rootPkg ?: "")) {
            Log.w(TAG, "[Security] Window package ($rootPkg) mismatch with event package ($packageName). Aborting inspection.")
            rootNode.recycle()
            cancelPendingAdvance()
            return
        }

        try {
            inspectAndProcessPlayerState(rootNode, adapter, packageName)
        } finally {
            rootNode.recycle()
        }
    }

    private fun checkPeriodicPlaybackState() {
        if (isPerformingNavigation) return
        val now = System.currentTimeMillis()
        if (now - lastNavigationTimestamp < 1500) return

        val rootNode = rootInActiveWindow ?: return
        val rootPkg = rootNode.packageName?.toString()
        if (rootPkg == null || !GalleryRegistry.isSupportedPackage(rootPkg)) {
            rootNode.recycle()
            return
        }

        val adapter = GalleryRegistry.findAdapter(rootPkg)
        if (adapter == null) {
            rootNode.recycle()
            return
        }

        try {
            inspectAndProcessPlayerState(rootNode, adapter, rootPkg)
        } finally {
            rootNode.recycle()
        }
    }

    private fun inspectAndProcessPlayerState(rootNode: AccessibilityNodeInfo, adapter: GalleryAdapter, packageName: String) {
        val isPlayerActive = adapter.isPlayerActive(rootNode)
        val isPhoto = adapter.isPhoto(rootNode)

        // Guard: Do not issue gestures when player state cannot be confirmed
        if (!isPlayerActive && !isPhoto) {
            return
        }

        if (isPlayerActive) {
            Log.d(TAG, "[PlayerDetected] Active video player confirmed in ${adapter.appName} ($packageName).")
        }

        val isVideoPaused = adapter.isVideoPaused(rootNode)
        // Condition 1: User manually paused playback -> Wait, hold automation
        if (isVideoPaused) {
            cancelPendingAdvance()
            return
        }

        val isVideoCompleted = adapter.isVideoCompleted(rootNode)
        val isVideoPlaying = adapter.isVideoPlaying(rootNode)

        Log.d(TAG, "[PlaybackMonitor] adapter=${adapter.appName} active=$isPlayerActive playing=$isVideoPlaying paused=$isVideoPaused completed=$isVideoCompleted isAdvanceScheduled=$isAdvanceScheduled")

        // Condition 2: Video completed (reached end, looping wrap-around, or duration match) -> Advance
        if (isVideoCompleted) {
            Log.i(TAG, "[CompletionDetection] Video completion detected via ${adapter.appName}. Scheduling advance in 350ms.")
            scheduleAdvance(delayMs = 350)
            return
        }

        // Condition 3: Video is actively playing -> Monitor, do not advance
        if (isVideoPlaying) {
            // Awaken controls once if duration is unknown and no advance is scheduled
            if (!isAdvanceScheduled && adapter.needsControlAwakening()) {
                adapter.onControlsAwakened()
                Log.d(TAG, "[PlaybackMonitor] One-time gentle control awakening to expose seekbar/duration.")
                awakenControls()
            }
            return
        }

        // Condition 4: Photo detected -> Skip photo
        if (isPhoto) {
            Log.i(TAG, "[PhotoDetection] Photo detected in ${adapter.appName}. Scheduling photo skip in 600ms.")
            scheduleAdvance(delayMs = 600)
            return
        }
    }

    private fun awakenControls() {
        val displayMetrics = resources.displayMetrics
        val x = displayMetrics.widthPixels * 0.5f
        val y = displayMetrics.heightPixels * 0.25f
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun scheduleAdvance(delayMs: Long) {
        if (isAdvanceScheduled) return
        isAdvanceScheduled = true
        handler.removeCallbacks(advanceRunnable)
        handler.postDelayed(advanceRunnable, delayMs)
    }

    private fun cancelPendingAdvance() {
        isAdvanceScheduled = false
        handler.removeCallbacks(advanceRunnable)
    }

    private fun performNavigation() {
        isAdvanceScheduled = false
        if (isPerformingNavigation) return

        val currentState = AutomationCoordinator.state.value
        val rootNode = rootInActiveWindow
        val currentPkg = rootNode?.packageName?.toString()

        // Strict pre-navigation validation after delay
        val validation = NavigationSecurityPolicy.validatePreNavigation(
            currentState = currentState,
            isRootNull = rootNode == null,
            rootPackage = currentPkg
        )

        if (validation is NavigationSecurityPolicy.ValidationResult.Rejected) {
            Log.w(TAG, "[Security] Aborting navigation: ${validation.reason}")
            rootNode?.recycle()
            isPerformingNavigation = false
            return
        }

        val safeRoot = rootNode!!
        val safePkg = currentPkg!!
        val adapter = GalleryRegistry.findAdapter(safePkg)
        if (adapter == null) {
            Log.w(TAG, "[Security] Aborting navigation: no adapter found for '$safePkg'")
            safeRoot.recycle()
            isPerformingNavigation = false
            return
        }

        val prevSignature = extractScreenSignature(safeRoot, adapter)
        isPerformingNavigation = true
        lastNavigationTimestamp = System.currentTimeMillis()

        // 1. Prefer visible next-video control when available
        val nextBtn = adapter.findNextButton(safeRoot)
        if (nextBtn != null) {
            val clickCheckRoot = rootInActiveWindow
            val clickCheckPkg = clickCheckRoot?.packageName?.toString()
            val preClickCheck = NavigationSecurityPolicy.validatePreDispatch(
                currentState = AutomationCoordinator.state.value,
                expectedPackage = safePkg,
                isRootNull = clickCheckRoot == null,
                currentRootPackage = clickCheckPkg
            )
            clickCheckRoot?.recycle()

            if (preClickCheck is NavigationSecurityPolicy.ValidationResult.Rejected) {
                Log.w(TAG, "[Security] Aborting ACTION_CLICK: ${preClickCheck.reason}")
                nextBtn.recycle()
                safeRoot.recycle()
                isPerformingNavigation = false
                return
            }

            Log.i(TAG, "[NavigationAction] Visible next button found in ${adapter.appName}. Performing ACTION_CLICK.")
            val clicked = nextBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            nextBtn.recycle()
            safeRoot.recycle()
            Log.i(TAG, "[NavigationResult] ACTION_CLICK dispatched. Succeeded: $clicked")

            if (clicked) {
                scheduleVerification(prevSignature, safePkg)
                return
            }
        } else {
            safeRoot.recycle()
        }

        // 2. Fall back to center-screen horizontal swipe gesture for verified gallery
        Log.i(TAG, "[NavigationAction] Dispatching horizontal swipe gesture for ${adapter.appName}.")
        dispatchSwipeGesture(safePkg) {
            scheduleVerification(prevSignature, safePkg)
        }
    }

    private fun dispatchSwipeGesture(expectedPkg: String, onComplete: () -> Unit) {
        val gestureCheckRoot = rootInActiveWindow
        val gestureCheckPkg = gestureCheckRoot?.packageName?.toString()
        val preGestureCheck = NavigationSecurityPolicy.validatePreDispatch(
            currentState = AutomationCoordinator.state.value,
            expectedPackage = expectedPkg,
            isRootNull = gestureCheckRoot == null,
            currentRootPackage = gestureCheckPkg
        )
        gestureCheckRoot?.recycle()

        if (preGestureCheck is NavigationSecurityPolicy.ValidationResult.Rejected) {
            Log.w(TAG, "[Security] Aborting swipe gesture: ${preGestureCheck.reason}")
            isPerformingNavigation = false
            return
        }

        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()

        // Center horizontal swipe from LEFT to RIGHT as mandated by specification
        // Vertical position centerY = height * 0.45f avoids important player controls (seekbar and toolbar)
        val startX = width * 0.10f
        val endX = width * 0.88f
        val centerY = height * 0.45f

        val path = Path().apply {
            moveTo(startX, centerY)
            lineTo(endX, centerY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 220)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        Log.i(TAG, "[GestureDispatched] Rightward horizontal swipe dispatched: startX=$startX, endX=$endX, centerY=$centerY for $expectedPkg.")

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.i(TAG, "[GestureCallbackCompleted] Swipe gesture completed successfully by system.")
                onComplete()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "[GestureCancelled] Swipe gesture cancelled by system.")
                isPerformingNavigation = false
            }
        }, null)
    }

    private fun scheduleVerification(prevSignature: String?, expectedPkg: String) {
        // Wait 900ms for gallery transition animation to complete
        handler.postDelayed({
            verifyNavigationResult(prevSignature, expectedPkg)
        }, 900)
    }

    private fun verifyNavigationResult(prevSignature: String?, expectedPkg: String) {
        if (AutomationCoordinator.state.value != AutomationCoordinator.State.RUNNING) {
            isPerformingNavigation = false
            return
        }

        val newRoot = rootInActiveWindow
        val newPkg = newRoot?.packageName?.toString()
        if (newRoot == null || newPkg != expectedPkg || !GalleryRegistry.isSupportedPackage(newPkg)) {
            Log.w(TAG, "[Security] Post-navigation window is not expected gallery ($newPkg). Cancelling verification.")
            newRoot?.recycle()
            isPerformingNavigation = false
            return
        }

        val adapter = GalleryRegistry.findAdapter(newPkg)
        val newSignature = extractScreenSignature(newRoot, adapter)
        newRoot.recycle()

        isPerformingNavigation = false

        if (prevSignature != null && newSignature != null && prevSignature == newSignature) {
            consecutiveNavigationFailures++
            Log.w(TAG, "[MediaItemUnchanged] Media item unchanged after rightward gesture. Consecutive failures: $consecutiveNavigationFailures/2. (Possible seek gesture or end of album)")

            if (consecutiveNavigationFailures >= 2) {
                Log.i(TAG, "[LastVideoTermination] End of album reached or horizontal swipe is not a navigation gesture. Terminating automation cleanly.")
                AutomationCoordinator.stop()
                consecutiveNavigationFailures = 0
            } else {
                adapter?.onNavigationFailed()
            }
        } else {
            consecutiveNavigationFailures = 0
            Log.i(TAG, "[MediaItemChanged] Media item change confirmed: prev='$prevSignature' -> new='$newSignature'.")
            scheduleNextVideoPlaybackVerification(newPkg)
        }
    }

    private fun scheduleNextVideoPlaybackVerification(expectedPkg: String) {
        handler.postDelayed({
            if (AutomationCoordinator.state.value != AutomationCoordinator.State.RUNNING) return@postDelayed
            val root = rootInActiveWindow ?: return@postDelayed
            try {
                val pkg = root.packageName?.toString() ?: ""
                val adapter = GalleryRegistry.findAdapter(pkg)
                if (pkg == expectedPkg && adapter != null) {
                    val isPlayerActive = adapter.isPlayerActive(root)
                    val isPlaying = adapter.isVideoPlaying(root)
                    Log.i(TAG, "[NextVideoPlayback] Next video playback check in ${adapter.appName}: playerActive=$isPlayerActive, isPlaying=$isPlaying.")
                }
            } finally {
                root.recycle()
            }
        }, 700)
    }

    private fun extractScreenSignature(root: AccessibilityNodeInfo?, adapter: GalleryAdapter? = null): String? {
        if (root == null) return null
        val itemSig = adapter?.getActiveItemSignature(root)
        if (!itemSig.isNullOrEmpty()) {
            return itemSig
        }

        val sb = StringBuilder()
        val rect = android.graphics.Rect()
        var nodeCount = 0
        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 8) return
            nodeCount++
            node.getBoundsInScreen(rect)
            node.viewIdResourceName?.let { sb.append(it).append("|") }
            sb.append(node.className).append("@").append(rect.toShortString()).append(";")
            node.text?.let { sb.append("t:").append(it).append(";") }
            node.contentDescription?.let { sb.append("d:").append(it).append(";") }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    traverse(child, depth + 1)
                    child.recycle()
                }
            }
        }
        traverse(root, 0)
        sb.append("count=").append(nodeCount)
        return "screen_${sb.toString().hashCode()}"
    }

    override fun onInterrupt() {
        Log.d(TAG, "[AccessibilityConnection] Service interrupted.")
        cancelPendingAdvance()
        handler.removeCallbacks(heartbeatRunnable)
        isPerformingNavigation = false
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "[AccessibilityConnection] Service unbound.")
        AutomationCoordinator.removeListener(coordinatorListener)
        cancelPendingAdvance()
        handler.removeCallbacks(heartbeatRunnable)
        isPerformingNavigation = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.d(TAG, "[AccessibilityConnection] Service destroyed.")
        AutomationCoordinator.removeListener(coordinatorListener)
        cancelPendingAdvance()
        handler.removeCallbacks(heartbeatRunnable)
        isPerformingNavigation = false
        super.onDestroy()
    }
}
