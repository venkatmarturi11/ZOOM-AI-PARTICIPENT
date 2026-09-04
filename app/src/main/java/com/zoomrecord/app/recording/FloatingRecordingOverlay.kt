package com.zoomrecord.app.recording

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.zoomrecord.app.zoom.ZoomBotAccessibilityService

/**
 * High-performance, edge-docking Floating REC HUD Controller modeled after
 * premier screen recording apps (XRecorder, AZ Screen Recorder).
 *
 * Features:
 * 1. Compact Circular Bubble:
 *    - Vibrant orange/red recording camera ball that stays unobtrusively on screen.
 *    - Pulsing live REC indicator.
 * 2. Auto-Dock & Hide:
 *    - Snaps smoothly to the nearest screen edge (left or right) upon release.
 *    - After 3 seconds of inactivity, auto-dims (40% opacity) and tucks 50% into the screen edge.
 * 3. One-Touch Expandable Menu:
 *    - Tap springs open full recording controls: Live timer, Pause/Resume, Stop, and Collapse.
 *    - Automatically aligns menu based on whether docked on left or right edge.
 * 4. Auto-Collapse Timer:
 *    - Returns to tucked edge mode when dismissed or after inactivity.
 */
class FloatingRecordingOverlay(
    private val context: Context,
    private val onStopClicked: () -> Unit,
) {
    companion object {
        private const val TAG = "FloatingOverlay"
        private const val AUTO_HIDE_DELAY_MS = 3000L
        private const val BUBBLE_SIZE_DP = 52

        fun canDrawOverlay(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        }
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootLayout: LinearLayout? = null
    private var bubbleContainer: FrameLayout? = null
    private var menuContainer: LinearLayout? = null
    private var timerTextView: TextView? = null
    private var botStatusTextView: TextView? = null
    private var pauseResumeBtn: TextView? = null
    private var speakerBtn: TextView? = null
    private var rotateBtn: TextView? = null
    private var stopBtn: TextView? = null
    private var recDotView: View? = null

    private lateinit var wmParams: WindowManager.LayoutParams
    private var orientationLockView: View? = null
    private var orientationParams: WindowManager.LayoutParams? = null

    private var isShowing = false
    private var isPaused = false
    private var isSpeakerOn = true
    private var isLandscape = true // Default Horizontal (Landscape) after joining
    private var isExpanded = false
    private var isDockedOnRight = false
    private var isTucked = false

    private val autoHideRunnable = Runnable {
        tuckIntoEdge()
    }

    private val autoCollapseRunnable = Runnable {
        if (isExpanded) {
            collapseMenu()
        }
    }

    // ── Broadcast Receivers ──────────────────────────────────────────

    private val botStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(ZoomBotAccessibilityService.EXTRA_STATUS_TEXT) ?: return
            val isConnected = intent.getBooleanExtra(ZoomBotAccessibilityService.EXTRA_IS_CONNECTED, false)
            botStatusTextView?.post {
                botStatusTextView?.text = text
                if (isConnected) {
                    botStatusTextView?.setTextColor(Color.parseColor("#4ADE80"))
                } else {
                    botStatusTextView?.setTextColor(Color.parseColor("#93C5FD"))
                }
            }
        }
    }

    private val recordingStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val paused = intent?.getBooleanExtra(ScreenRecordService.EXTRA_IS_PAUSED, false) ?: false
            val elapsed = intent?.getIntExtra(ScreenRecordService.EXTRA_ELAPSED_SECONDS, 0) ?: 0
            updatePausedState(paused)
            updateElapsedSeconds(elapsed, paused)
        }
    }

    private val speakerStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val enabled = intent?.getBooleanExtra(ScreenRecordService.EXTRA_SPEAKER_STATE, false) ?: false
            updateSpeakerState(enabled)
        }
    }

    private fun togglePauseResume() {
        val nextPaused = !isPaused
        updatePausedState(nextPaused)
        val serviceIntent = Intent(context, ScreenRecordService::class.java).apply {
            action = if (nextPaused) ScreenRecordService.ACTION_PAUSE else ScreenRecordService.ACTION_RESUME
        }
        context.startService(serviceIntent)
        resetAutoCollapseTimer()
    }

    private fun toggleSpeaker() {
        val next = !isSpeakerOn
        updateSpeakerState(next)
        val serviceIntent = Intent(context, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_TOGGLE_SPEAKER
        }
        context.startService(serviceIntent)
        resetAutoCollapseTimer()
    }

    fun updateSpeakerState(enabled: Boolean) {
        isSpeakerOn = enabled
        rootLayout?.post {
            speakerBtn?.text = if (enabled) "🔊" else "🔇"
            speakerBtn?.background = getSpeakerBtnDrawable(enabled)
        }
    }

    private fun getSpeakerBtnDrawable(enabled: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (enabled) {
                setColor(Color.parseColor("#0D72FF")) // Active Blue
                setStroke(dpToPx(1), Color.parseColor("#93C5FD"))
            } else {
                setColor(Color.parseColor("#334155")) // Muted Slate
                setStroke(dpToPx(1), Color.parseColor("#64748B"))
            }
        }
    }

    fun updatePausedState(paused: Boolean) {
        isPaused = paused
        rootLayout?.post {
            if (paused) {
                pauseResumeBtn?.text = "▶"
                val btnBg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#D97706")) // Amber
                }
                pauseResumeBtn?.background = btnBg

                recDotView?.clearAnimation()
                val dotBg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#F59E0B"))
                }
                recDotView?.background = dotBg
            } else {
                pauseResumeBtn?.text = "⏸"
                val btnBg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#334155")) // Dark Slate
                    setStroke(dpToPx(1), Color.parseColor("#64748B"))
                }
                pauseResumeBtn?.background = btnBg

                val dotBg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#EF4444")) // Red
                }
                recDotView?.background = dotBg

                val pulse = AlphaAnimation(1.0f, 0.2f).apply {
                    duration = 800
                    repeatMode = Animation.REVERSE
                    repeatCount = Animation.INFINITE
                }
                recDotView?.startAnimation(pulse)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (isShowing || windowManager == null) return
        if (!canDrawOverlay(context)) {
            Log.w(TAG, "Overlay permission (SYSTEM_ALERT_WINDOW) not granted — skipping floating HUD")
            return
        }

        try {
            val screenW = getScreenWidth()
            val bubblePx = dpToPx(BUBBLE_SIZE_DP)

            wmParams = WindowManager.LayoutParams().apply {
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                format = PixelFormat.TRANSLUCENT
                gravity = Gravity.TOP or Gravity.START
                x = 0 // Docked at left edge
                y = getScreenHeight() / 3
                width = WindowManager.LayoutParams.WRAP_CONTENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
            }

            // ── Root Container ─────────────────────────────────────────
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.TRANSPARENT)
            }
            rootLayout = root

            // ── Orientation Lock Overlay (Horizontal/Landscape by default) ──
            val oParams = WindowManager.LayoutParams().apply {
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                format = PixelFormat.TRANSLUCENT
                width = 0
                height = 0
                screenOrientation = if (isLandscape) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }
            orientationParams = oParams
            val oView = View(context)
            orientationLockView = oView
            try {
                windowManager.addView(oView, oParams)
                Log.i(TAG, "Screen orientation locked to ${if (isLandscape) "Horizontal/Landscape" else "Vertical/Portrait"}")
            } catch (e: Exception) {
                Log.w(TAG, "Notice: could not apply orientation lock overlay", e)
            }

            // ── 1. Circular Floating Bubble ────────────────────────────
            val bubble = FrameLayout(context).apply {
                val p = LinearLayout.LayoutParams(bubblePx, bubblePx)
                layoutParams = p

                val circleBg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    colors = intArrayOf(
                        Color.parseColor("#FF6D00"), // Radiant Deep Orange
                        Color.parseColor("#E65100")
                    )
                    gradientType = GradientDrawable.LINEAR_GRADIENT
                    setStroke(dpToPx(2), Color.WHITE)
                }
                background = circleBg
                elevation = dpToPx(8).toFloat()

                // Center camera / record icon
                val camIcon = TextView(context).apply {
                    text = "📹"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                addView(camIcon)

                // Pulsing Red REC corner badge
                val dot = View(context).apply {
                    val dotSize = dpToPx(11)
                    val lp = FrameLayout.LayoutParams(dotSize, dotSize).apply {
                        gravity = Gravity.TOP or Gravity.END
                        topMargin = dpToPx(3)
                        rightMargin = dpToPx(3)
                    }
                    layoutParams = lp

                    val dotBg = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#EF4444")) // Red
                        setStroke(dpToPx(1), Color.WHITE)
                    }
                    background = dotBg

                    val pulse = AlphaAnimation(1.0f, 0.2f).apply {
                        duration = 750
                        repeatMode = Animation.REVERSE
                        repeatCount = Animation.INFINITE
                    }
                    startAnimation(pulse)
                }
                recDotView = dot
                addView(dot)
            }
            bubbleContainer = bubble

            // ── 2. Expanded Control Menu ───────────────────────────────
            val menu = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val padH = dpToPx(10)
                val padV = dpToPx(6)
                setPadding(padH, padV, padH, padV)

                val menuBg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(26).toFloat()
                    setColor(Color.parseColor("#EE0F172A")) // Dark translucent glass
                    setStroke(dpToPx(1), Color.parseColor("#475569"))
                }
                background = menuBg
                elevation = dpToPx(10).toFloat()
                visibility = View.GONE // Collapsed by default
            }
            menuContainer = menu

            // Timer display
            val timerView = TextView(context).apply {
                text = "REC 00:00"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = dpToPx(4)
                    rightMargin = dpToPx(10)
                }
                layoutParams = lp
            }
            timerTextView = timerView
            menu.addView(timerView)

            // Pause / Resume circular action
            val pauseBtn = TextView(context).apply {
                text = "⏸"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                val size = dpToPx(32)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    rightMargin = dpToPx(8)
                }
                val btnBg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#334155"))
                    setStroke(dpToPx(1), Color.parseColor("#64748B"))
                }
                background = btnBg
                setOnClickListener { togglePauseResume() }
            }
            pauseResumeBtn = pauseBtn
            menu.addView(pauseBtn)

            // Speaker Output toggle circular action (Default: Silent / Muted)
            val spkBtn = TextView(context).apply {
                text = if (isSpeakerOn) "🔊" else "🔇"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                val size = dpToPx(32)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    rightMargin = dpToPx(8)
                }
                background = getSpeakerBtnDrawable(isSpeakerOn)
                setOnClickListener {
                    toggleSpeaker()
                }
            }
            speakerBtn = spkBtn
            menu.addView(spkBtn)

            // Screen Rotation Toggle circular action (Horizontal <-> Vertical)
            val rotBtn = TextView(context).apply {
                text = if (isLandscape) "🖥️" else "📱"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                val size = dpToPx(32)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    rightMargin = dpToPx(8)
                }
                background = getRotateBtnDrawable(isLandscape)
                setOnClickListener {
                    toggleOrientation()
                }
            }
            rotateBtn = rotBtn
            menu.addView(rotBtn)

            // Stop Recording circular action
            val stopAction = TextView(context).apply {
                text = "⏹"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                val size = dpToPx(32)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    rightMargin = dpToPx(8)
                }
                val btnBg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    colors = intArrayOf(Color.parseColor("#EF4444"), Color.parseColor("#DC2626"))
                    gradientType = GradientDrawable.LINEAR_GRADIENT
                    setStroke(dpToPx(1), Color.WHITE)
                }
                background = btnBg
                setOnClickListener {
                    Log.i(TAG, "Stop button clicked on floating overlay")
                    onStopClicked()
                }
            }
            stopBtn = stopAction
            menu.addView(stopAction)

            // Close / Hide button
            val closeAction = TextView(context).apply {
                text = "✕"
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                val size = dpToPx(26)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    rightMargin = dpToPx(2)
                }
                setOnClickListener {
                    collapseMenu()
                }
            }
            menu.addView(closeAction)

            // Initial view tree composition: Bubble on left, menu on right
            root.addView(bubble)
            root.addView(menu)

            // ── Drag & Tap Gesture Listener ────────────────────────────
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isDragging = false

            bubble.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        cancelAutoHide()
                        cancelAutoCollapse()
                        untuckFromEdge()

                        initialX = wmParams.x
                        initialY = wmParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 12 || Math.abs(dy) > 12) {
                            isDragging = true
                            if (isExpanded) {
                                collapseMenu()
                            }
                        }
                        if (isDragging) {
                            wmParams.x = initialX + dx
                            wmParams.y = initialY + dy
                            try {
                                windowManager.updateViewLayout(rootLayout, wmParams)
                            } catch (_: Exception) {}
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            // Tap gesture: Toggle Expand / Collapse
                            toggleMenu()
                        } else {
                            // Drag ended: Snap to closest edge
                            snapToNearestEdge()
                        }
                        true
                    }
                    else -> false
                }
            }

            windowManager.addView(root, wmParams)
            isShowing = true

            // Start initial auto-hide timer (tucks into edge after 3 seconds)
            scheduleAutoHide()

            // Register broadcast receivers
            try {
                val filter = IntentFilter(ZoomBotAccessibilityService.ACTION_BOT_STATUS)
                ContextCompat.registerReceiver(context, botStatusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            } catch (_: Exception) {}

            try {
                val stateFilter = IntentFilter(ScreenRecordService.BROADCAST_RECORDING_STATE)
                ContextCompat.registerReceiver(context, recordingStateReceiver, stateFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
            } catch (_: Exception) {}

            try {
                val speakerFilter = IntentFilter(ScreenRecordService.BROADCAST_SPEAKER_STATE)
                ContextCompat.registerReceiver(context, speakerStateReceiver, speakerFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
            } catch (_: Exception) {}

            Log.i(TAG, "Floating recording overlay displayed successfully with edge auto-hide")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to display floating overlay", e)
        }
    }

    // ── Menu Expand / Collapse Logic ─────────────────────────────────

    private fun toggleMenu() {
        if (isExpanded) {
            collapseMenu()
        } else {
            expandMenu()
        }
    }

    private fun expandMenu() {
        isExpanded = true
        cancelAutoHide()
        untuckFromEdge()

        val root = rootLayout ?: return
        val bubble = bubbleContainer ?: return
        val menu = menuContainer ?: return

        // Rearrange views depending on docked side
        root.removeAllViews()
        if (isDockedOnRight) {
            // Bubble on right, menu expands to the left
            root.addView(menu)
            root.addView(bubble)
            val screenW = getScreenWidth()
            // Shift x so the expanded menu doesn't get clipped off the right screen edge
            root.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val totalWidth = if (root.measuredWidth > 0) root.measuredWidth else dpToPx(BUBBLE_SIZE_DP + 250)
            wmParams.x = maxOf(0, screenW - totalWidth)
        } else {
            // Bubble on left, menu expands to the right
            root.addView(bubble)
            root.addView(menu)
            wmParams.x = 0
        }

        menu.visibility = View.VISIBLE
        updateViewLayout()
        resetAutoCollapseTimer()
    }

    private fun collapseMenu() {
        isExpanded = false
        cancelAutoCollapse()

        menuContainer?.visibility = View.GONE

        val root = rootLayout ?: return
        val bubble = bubbleContainer ?: return
        root.removeAllViews()
        root.addView(bubble)

        snapToNearestEdge()
    }

    // ── Edge Snapping & Auto-Hide Tuck ────────────────────────────────

    private fun snapToNearestEdge() {
        val screenW = getScreenWidth()
        val bubblePx = dpToPx(BUBBLE_SIZE_DP)
        val middle = screenW / 2

        if (wmParams.x + bubblePx / 2 > middle) {
            // Snap to right edge
            wmParams.x = screenW - bubblePx
            isDockedOnRight = true
        } else {
            // Snap to left edge
            wmParams.x = 0
            isDockedOnRight = false
        }

        // Clamp Y inside screen bounds
        val screenH = getScreenHeight()
        wmParams.y = wmParams.y.coerceIn(dpToPx(40), screenH - dpToPx(100))

        updateViewLayout()

        if (!isExpanded) {
            scheduleAutoHide()
        }
    }

    private fun tuckIntoEdge() {
        if (!isShowing || isExpanded || isTucked) return
        isTucked = true

        val screenW = getScreenWidth()
        val bubblePx = dpToPx(BUBBLE_SIZE_DP)
        val tuckOffset = dpToPx(24) // Tucks ~50% into screen edge

        if (isDockedOnRight) {
            wmParams.x = screenW - bubblePx + tuckOffset
        } else {
            wmParams.x = -tuckOffset
        }

        // Auto-dim to 45% transparency so it doesn't block Zoom meeting
        rootLayout?.alpha = 0.45f
        updateViewLayout()
    }

    private fun untuckFromEdge() {
        if (!isTucked) return
        isTucked = false

        val screenW = getScreenWidth()
        val bubblePx = dpToPx(BUBBLE_SIZE_DP)

        if (isDockedOnRight) {
            wmParams.x = screenW - bubblePx
        } else {
            wmParams.x = 0
        }

        rootLayout?.alpha = 1.0f
        updateViewLayout()
    }

    private fun updateViewLayout() {
        try {
            if (isShowing && rootLayout != null && windowManager != null) {
                windowManager.updateViewLayout(rootLayout, wmParams)
            }
        } catch (_: Exception) {}
    }

    private fun scheduleAutoHide() {
        cancelAutoHide()
        mainHandler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS)
    }

    private fun cancelAutoHide() {
        mainHandler.removeCallbacks(autoHideRunnable)
    }

    private fun resetAutoCollapseTimer() {
        cancelAutoCollapse()
        mainHandler.postDelayed(autoCollapseRunnable, 5000L)
    }

    private fun cancelAutoCollapse() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
    }

    private fun toggleOrientation() {
        isLandscape = !isLandscape
        updateOrientationLock(isLandscape)
        resetAutoCollapseTimer()
    }

    private fun updateOrientationLock(landscape: Boolean) {
        isLandscape = landscape
        val oView = orientationLockView ?: return
        val oParams = orientationParams ?: return
        oParams.screenOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        try {
            windowManager?.updateViewLayout(oView, oParams)
            Log.i(TAG, "Screen rotation switched to ${if (landscape) "Horizontal (Landscape)" else "Vertical (Portrait)"}")
        } catch (_: Exception) {}

        rootLayout?.post {
            rotateBtn?.text = if (landscape) "🖥️" else "📱"
            rotateBtn?.background = getRotateBtnDrawable(landscape)
        }
    }

    private fun getRotateBtnDrawable(landscape: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (landscape) {
                setColor(Color.parseColor("#0F766E")) // Active Deep Teal
                setStroke(dpToPx(1), Color.parseColor("#5EEAD4"))
            } else {
                setColor(Color.parseColor("#334155")) // Muted Slate
                setStroke(dpToPx(1), Color.parseColor("#64748B"))
            }
        }
    }

    // ── Public Update & Teardown APIs ────────────────────────────────

    fun updateElapsedSeconds(elapsedSec: Int, paused: Boolean = isPaused) {
        val hours = elapsedSec / 3600
        val minutes = (elapsedSec % 3600) / 60
        val seconds = elapsedSec % 60

        val prefix = if (paused) "PAUSE" else "REC"
        val formatted = if (hours > 0) {
            String.format("$prefix %02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("$prefix %02d:%02d", minutes, seconds)
        }

        timerTextView?.post {
            timerTextView?.text = formatted
            if (paused) {
                timerTextView?.setTextColor(Color.parseColor("#FBBF24"))
            } else {
                timerTextView?.setTextColor(Color.WHITE)
            }
        }
    }

    fun dismiss() {
        if (!isShowing || rootLayout == null || windowManager == null) return
        try {
            cancelAutoHide()
            cancelAutoCollapse()

            try {
                context.unregisterReceiver(botStatusReceiver)
            } catch (_: Exception) {}
            try {
                context.unregisterReceiver(recordingStateReceiver)
            } catch (_: Exception) {}
            try {
                context.unregisterReceiver(speakerStateReceiver)
            } catch (_: Exception) {}

            if (orientationLockView != null) {
                try {
                    windowManager.removeView(orientationLockView)
                } catch (_: Exception) {}
                orientationLockView = null
                orientationParams = null
            }

            windowManager.removeView(rootLayout)
            rootLayout = null
            bubbleContainer = null
            menuContainer = null
            timerTextView = null
            botStatusTextView = null
            pauseResumeBtn = null
            speakerBtn = null
            stopBtn = null
            recDotView = null
            isShowing = false
            Log.i(TAG, "Floating recording overlay dismissed")
        } catch (e: Exception) {
            Log.w(TAG, "Error removing floating overlay", e)
        }
    }

    private fun getScreenWidth(): Int {
        return context.resources.displayMetrics.widthPixels
    }

    private fun getScreenHeight(): Int {
        return context.resources.displayMetrics.heightPixels
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
