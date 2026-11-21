package com.autoalbum.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.autoalbum.automation.AutomationCoordinator
import kotlin.math.abs

/**
 * Floating Controller Service (Enforced Singleton).
 *
 * Requirements:
 * - Singleton lifecycle: exactly one service and one floating arrow exist.
 * - Repeated START requests update existing service, never creating duplicate views.
 * - Initial state: small draggable arrow only.
 * - Tap arrow: reveals compact START / PAUSE / STOP menu.
 * - Tap outside menu or select a control: collapses menu, leaving arrow.
 * - STOP: sets state to STOPPED, removes all overlay views immediately,
 *   stops foreground service cleanly without leaving stale overlays.
 */
class FloatingControlService : Service() {

    companion object {
        private const val TAG = "AutoAlbum"
        const val ACTION_START = "com.autoalbum.action.START"
        const val ACTION_STOP = "com.autoalbum.action.STOP"

        @Volatile
        var isRunning: Boolean = false
            internal set

        @Volatile
        internal var activeInstance: FloatingControlService? = null

        fun isServiceActive(): Boolean = isRunning && activeInstance != null

        fun start(context: Context) {
            val intent = Intent(context, FloatingControlService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            activeInstance?.stopController()
            val intent = Intent(context, FloatingControlService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "[FloatingService] Failed to send stop intent: ${e.message}")
            }
        }
    }

    private lateinit var windowManager: WindowManager
    internal val overlayTracker by lazy { OverlayViewTracker(windowManager) }

    // Floating arrow view references
    private var arrowTextView: TextView? = null
    private var runningIndicatorDot: View? = null

    // Menu button references for state synchronization
    private var btnStart: Button? = null
    private var btnPause: Button? = null
    private var btnStop: Button? = null

    // Drag touch state
    private var downRawX = 0f
    private var downRawY = 0f
    private var startParamsX = 0
    private var startParamsY = 0
    private var lastTapTimestamp = 0L

    // Screen lock broadcast receiver
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                Log.d(TAG, "[FloatingService] Screen off detected. Stopping automation controller.")
                stopController()
            }
        }
    }

    // Call state listeners
    private var telephonyCallback: Any? = null
    private var phoneStateListener: PhoneStateListener? = null

    // Coordinator state listener
    private val stateListener: (AutomationCoordinator.State) -> Unit = { state ->
        Log.d(TAG, "[FloatingService] Coordinator transitioned to $state")
        if (state == AutomationCoordinator.State.STOPPED) {
            stopController()
        } else {
            updateHandleAppearance(state)
            refreshButtonHighlights(state)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "[FloatingService] onCreate() called.")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Singleton enforcement: if an old instance exists, terminate it cleanly
        activeInstance?.let { existing ->
            if (existing != this) {
                Log.w(TAG, "[FloatingService] Stale service instance detected. Cleaning up stale instance.")
                existing.stopController()
            }
        }
        activeInstance = this
        isRunning = true

        createNotificationChannel()
        startForeground(101, buildNotification())

        registerScreenOffReceiver()
        registerCallStateListener()

        AutomationCoordinator.addListener(stateListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.d(TAG, "[FloatingService] onStartCommand action=$action startId=$startId")

        when (action) {
            ACTION_STOP -> {
                stopController()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                handleStartRequest()
            }
        }

        return START_NOT_STICKY
    }

    private fun handleStartRequest() {
        // If arrow handle is not attached and permission is granted, create and attach arrow
        if (!overlayTracker.hasArrow()) {
            if (Settings.canDrawOverlays(this)) {
                showFloatingArrow()
                // Requirement E: LAUNCH starts controller without activating automation
                updateHandleAppearance(AutomationCoordinator.state.value)
            } else {
                Log.w(TAG, "[FloatingService] Cannot show overlay: SYSTEM_ALERT_WINDOW not granted.")
            }
        } else {
            // Repeated request: ensure compact menu is dismissed, leaving only the arrow
            overlayTracker.dismissMenu()
            updateHandleAppearance(AutomationCoordinator.state.value)
            Log.d(TAG, "[FloatingService] Controller already attached. Re-synchronized to arrow-only.")
        }
    }

    private fun showFloatingArrow() {
        if (overlayTracker.hasArrow()) return

        val container = FrameLayout(this).apply {
            contentDescription = "AutoAlbum floating arrow handle"
            setOnTouchListener { view, event -> handleTouch(view, event) }
            setOnClickListener { toggleCompactMenu() }
        }

        val handleSizePx = dpToPx(44)
        val initialStroke = if (AutomationCoordinator.state.value == AutomationCoordinator.State.RUNNING) {
            Color.parseColor("#EF4444")
        } else if (AutomationCoordinator.state.value == AutomationCoordinator.State.PAUSED) {
            Color.parseColor("#F59E0B")
        } else {
            Color.parseColor("#38BDF8")
        }

        val shape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#1A2130"))
            setStroke(dpToPx(2), initialStroke)
        }

        val arrow = TextView(this).apply {
            text = "›"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = shape
            layoutParams = FrameLayout.LayoutParams(handleSizePx, handleSizePx, Gravity.CENTER)
        }
        arrowTextView = arrow
        container.addView(arrow)

        // Small red running indicator dot (visible when RUNNING)
        val dotSizePx = dpToPx(10)
        val dotShape = GradientDrawable().apply {
            setShape(GradientDrawable.OVAL)
            setColor(Color.parseColor("#EF4444")) // Red running indicator
        }
        val dot = View(this).apply {
            background = dotShape
            layoutParams = FrameLayout.LayoutParams(dotSizePx, dotSizePx, Gravity.TOP or Gravity.END).apply {
                setMargins(0, dpToPx(2), dpToPx(2), 0)
            }
            visibility = if (AutomationCoordinator.state.value == AutomationCoordinator.State.RUNNING) View.VISIBLE else View.GONE
        }
        runningIndicatorDot = dot
        container.addView(dot)

        val totalSizePx = dpToPx(50)
        val params = WindowManager.LayoutParams(
            totalSizePx,
            totalSizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = dpToPx(200)
        }

        overlayTracker.attachArrow(container, params)
    }

    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        val params = view.layoutParams as WindowManager.LayoutParams
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startParamsX = params.x
                startParamsY = params.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = (event.rawX - downRawX).toInt()
                val deltaY = (event.rawY - downRawY).toInt()
                params.x = startParamsX + deltaX
                params.y = startParamsY + deltaY
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (e: Exception) {
                    Log.w(TAG, "[FloatingService] updateViewLayout failed: ${e.message}")
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val moved = abs(event.rawX - downRawX) > dpToPx(8) || abs(event.rawY - downRawY) > dpToPx(8)
                if (!moved) {
                    val now = System.currentTimeMillis()
                    // Double tap within 350ms -> Immediately stop automation controller
                    if (now - lastTapTimestamp < 350) {
                        Log.d(TAG, "[FloatingService] Double tap detected. Stopping automation controller.")
                        stopController()
                    } else {
                        view.performClick()
                    }
                    lastTapTimestamp = now
                }
                return true
            }
        }
        return false
    }

    private fun toggleCompactMenu() {
        if (overlayTracker.isMenuVisible()) {
            dismissCompactMenu()
            return
        }

        val handle = overlayTracker.arrowHandleView ?: return
        val handleParams = handle.layoutParams as WindowManager.LayoutParams

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#131722"))
                cornerRadius = dpToPx(14).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#263246"))
            }
            // Dismiss menu when tapping outside its boundaries
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    Log.d(TAG, "[FloatingService] Tapped outside menu. Dismissing compact menu.")
                    dismissCompactMenu()
                    true
                } else {
                    false
                }
            }
        }

        fun createMenuButton(label: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(40)
                ).apply {
                    setMargins(0, dpToPx(4), 0, dpToPx(4))
                }
                setOnClickListener { onClick() }
            }
        }

        // START Button: resumes automation and collapses menu to arrow
        val startBtn = createMenuButton("START") {
            Log.d(TAG, "[MenuAction] START clicked. Resuming automation.")
            AutomationCoordinator.start()
            dismissCompactMenu()
        }
        btnStart = startBtn
        panel.addView(startBtn)

        // PAUSE Button: suspends automation and collapses menu to arrow
        val pauseBtn = createMenuButton("PAUSE") {
            Log.d(TAG, "[MenuAction] PAUSE clicked. Suspending automation.")
            AutomationCoordinator.pause()
            dismissCompactMenu()
        }
        btnPause = pauseBtn
        panel.addView(pauseBtn)

        // STOP Button: terminates automation and tears down controller completely
        val stopBtn = createMenuButton("STOP") {
            Log.d(TAG, "[MenuAction] STOP clicked. Stopping automation controller.")
            stopController()
        }
        btnStop = stopBtn
        panel.addView(stopBtn)

        refreshButtonHighlights(AutomationCoordinator.state.value)

        val menuParams = WindowManager.LayoutParams(
            dpToPx(130),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (handleParams.x + dpToPx(56)).coerceAtLeast(0)
            y = (handleParams.y - dpToPx(20)).coerceAtLeast(dpToPx(24))
        }

        overlayTracker.attachMenu(panel, menuParams)
    }

    private fun refreshButtonHighlights(state: AutomationCoordinator.State) {
        btnStart?.let { applyButtonStyle(it, MenuButtonType.START, state) }
        btnPause?.let { applyButtonStyle(it, MenuButtonType.PAUSE, state) }
        btnStop?.let { applyButtonStyle(it, MenuButtonType.STOP, state) }
    }

    private fun applyButtonStyle(button: Button, type: MenuButtonType, state: AutomationCoordinator.State) {
        val bgHex = FloatingMenuColorHelper.getButtonBackgroundColor(type, state)
        val shape = GradientDrawable().apply {
            setColor(Color.parseColor(bgHex))
            cornerRadius = dpToPx(8).toFloat()
        }
        button.background = shape
        button.setTextColor(Color.parseColor(FloatingMenuColorHelper.COLOR_TEXT_WHITE))
    }

    private fun dismissCompactMenu() {
        overlayTracker.dismissMenu()
        btnStart = null
        btnPause = null
        btnStop = null
    }

    private fun updateHandleAppearance(state: AutomationCoordinator.State) {
        val strokeColor = when (state) {
            AutomationCoordinator.State.RUNNING -> Color.parseColor("#EF4444") // Small red running indicator outline
            AutomationCoordinator.State.PAUSED -> Color.parseColor("#F59E0B")  // Amber
            else -> Color.parseColor("#38BDF8") // Cyan
        }
        val shape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#1A2130"))
            setStroke(dpToPx(2), strokeColor)
        }
        arrowTextView?.background = shape

        // Requirement E: Show small red running indicator when RUNNING
        runningIndicatorDot?.visibility = if (state == AutomationCoordinator.State.RUNNING) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    @Volatile
    private var isStopping = false

    /**
     * Authoritative teardown path for FloatingControlService.
     * Guaranteed to remove all overlay views immediately, notify coordinator,
     * unregister listeners, and stop the service cleanly.
     */
    @Synchronized
    fun stopController() {
        if (isStopping) return
        isStopping = true
        Log.d(TAG, "[FloatingService] Executing stopController(). Clean teardown initiated.")

        // 1. Set coordinator state to STOPPED (cancels pending navigation in AccessibilityService)
        AutomationCoordinator.stop()

        // 2. Remove all attached overlay views immediately via WindowManager.removeViewImmediate()
        overlayTracker.removeAll()
        arrowTextView = null
        runningIndicatorDot = null

        // 3. Unregister screen lock receiver and telephony listeners
        unregisterReceiversAndListeners()

        // 4. Detach coordinator listener
        AutomationCoordinator.removeListener(stateListener)

        // 5. Clear button references
        btnStart = null
        btnPause = null
        btnStop = null

        // 6. Stop foreground notification and service
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "[FloatingService] Exception stopping foreground: ${e.message}")
        }

        stopSelf()

        if (activeInstance == this) {
            activeInstance = null
            isRunning = false
        }
        Log.d(TAG, "[FloatingService] Teardown complete. Service stopped.")
    }

    private fun registerScreenOffReceiver() {
        try {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            registerReceiver(screenOffReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "[FloatingService] Unable to register screenOffReceiver: ${e.message}")
        }
    }

    private fun registerCallStateListener() {
        try {
            val telephony = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        if (state == TelephonyManager.CALL_STATE_RINGING) {
                            Log.d(TAG, "[FloatingService] Incoming call ringing. Pausing automation.")
                            AutomationCoordinator.onIncomingCall()
                        }
                    }
                }
                telephonyCallback = callback
                telephony.registerTelephonyCallback(mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        if (state == TelephonyManager.CALL_STATE_RINGING) {
                            Log.d(TAG, "[FloatingService] Incoming call ringing. Pausing automation.")
                            AutomationCoordinator.onIncomingCall()
                        }
                    }
                }
                phoneStateListener = listener
                @Suppress("DEPRECATION")
                telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (e: Exception) {
            Log.w(TAG, "[FloatingService] Unable to register call state listener: ${e.message}")
        }
    }

    private fun unregisterReceiversAndListeners() {
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (ignored: Exception) {}

        try {
            val telephony = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (telephonyCallback as? TelephonyCallback)?.let {
                    telephony.unregisterTelephonyCallback(it)
                }
            } else {
                phoneStateListener?.let {
                    @Suppress("DEPRECATION")
                    telephony.listen(it, PhoneStateListener.LISTEN_NONE)
                }
            }
        } catch (ignored: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "autoalbum_fgs",
                "AutoAlbum Automation Controller",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status of AutoAlbum video playback automation"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, "autoalbum_fgs")
        .setContentTitle("AutoAlbum Active")
        .setContentText("Video automation floating controller is active")
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "[FloatingService] onTaskRemoved() called.")
        if (AutomationCoordinator.state.value == AutomationCoordinator.State.STOPPED) {
            stopController()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "[FloatingService] onDestroy() called.")
        stopController()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
