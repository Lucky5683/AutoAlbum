package com.autoalbum.overlay

import android.util.Log
import android.view.View
import android.view.WindowManager
import java.util.Collections

/**
 * Thread-safe overlay view tracker that guarantees:
 * 1. Only ONE arrow handle view can be attached at any time.
 * 2. Only ONE compact menu view can be attached at any time.
 * 3. Prevents duplicate WindowManager.addView() calls.
 * 4. Provides synchronous, safe view removal using WindowManager.removeViewImmediate().
 * 5. Sweeps all attached views upon teardown.
 */
class OverlayViewTracker(private val windowManager: WindowManager?) {

    companion object {
        private const val TAG = "AutoAlbum"
    }

    private val _attachedViews = Collections.synchronizedSet(mutableSetOf<View>())
    val attachedViews: Set<View> get() = _attachedViews

    @Volatile
    var arrowHandleView: View? = null
        private set

    @Volatile
    var compactMenuView: View? = null
        private set

    fun hasArrow(): Boolean = arrowHandleView != null

    fun isMenuVisible(): Boolean = compactMenuView != null

    @Synchronized
    fun attachArrow(view: View, params: WindowManager.LayoutParams): Boolean {
        if (arrowHandleView != null || _attachedViews.contains(view) || view.isAttachedToWindow) {
            Log.w(TAG, "[OverlayTracker] Arrow handle view is already attached. Duplicate addView prevented.")
            return false
        }
        return try {
            windowManager?.addView(view, params)
            arrowHandleView = view
            _attachedViews.add(view)
            Log.d(TAG, "[OverlayTracker] Arrow handle view attached successfully.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[OverlayTracker] Failed to add arrow handle view: ${e.message}", e)
            false
        }
    }

    @Synchronized
    fun attachMenu(view: View, params: WindowManager.LayoutParams): Boolean {
        if (compactMenuView != null || _attachedViews.contains(view) || view.isAttachedToWindow) {
            Log.w(TAG, "[OverlayTracker] Compact menu view is already attached. Duplicate addView prevented.")
            return false
        }
        return try {
            windowManager?.addView(view, params)
            compactMenuView = view
            _attachedViews.add(view)
            Log.d(TAG, "[OverlayTracker] Compact menu view attached successfully.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[OverlayTracker] Failed to add compact menu view: ${e.message}", e)
            false
        }
    }

    @Synchronized
    fun dismissMenu(): Boolean {
        val menu = compactMenuView ?: return false
        return try {
            if (menu.isAttachedToWindow || _attachedViews.contains(menu)) {
                windowManager?.removeViewImmediate(menu)
            }
            Log.d(TAG, "[OverlayTracker] Compact menu dismissed immediately.")
            true
        } catch (e: Exception) {
            Log.w(TAG, "[OverlayTracker] Exception during removeViewImmediate for menu: ${e.message}")
            try {
                windowManager?.removeView(menu)
            } catch (ignored: Exception) {}
            false
        } finally {
            _attachedViews.remove(menu)
            compactMenuView = null
        }
    }

    @Synchronized
    fun removeAll(): Int {
        var removedCount = 0
        if (dismissMenu()) {
            removedCount++
        }

        arrowHandleView?.let { arrow ->
            try {
                if (arrow.isAttachedToWindow || _attachedViews.contains(arrow)) {
                    windowManager?.removeViewImmediate(arrow)
                }
                removedCount++
                Log.d(TAG, "[OverlayTracker] Arrow handle removed immediately.")
            } catch (e: Exception) {
                Log.w(TAG, "[OverlayTracker] Exception during removeViewImmediate for arrow: ${e.message}")
                try {
                    windowManager?.removeView(arrow)
                } catch (ignored: Exception) {}
            } finally {
                _attachedViews.remove(arrow)
                arrowHandleView = null
            }
        }

        // Final safety sweep of any stray views in _attachedViews
        val strayViews = _attachedViews.toList()
        for (view in strayViews) {
            try {
                windowManager?.removeViewImmediate(view)
                removedCount++
            } catch (e: Exception) {
                try { windowManager?.removeView(view) } catch (ignored: Exception) {}
            } finally {
                _attachedViews.remove(view)
            }
        }

        Log.d(TAG, "[OverlayTracker] removeAll() finished. Removed $removedCount view(s). Remaining: ${_attachedViews.size}")
        return removedCount
    }
}
