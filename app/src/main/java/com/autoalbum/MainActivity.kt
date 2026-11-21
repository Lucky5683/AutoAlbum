package com.autoalbum

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.autoalbum.automation.AutoAlbumAccessibilityService
import com.autoalbum.automation.AutomationCoordinator
import com.autoalbum.overlay.FloatingControlService
import com.autoalbum.ui.HomeScreen
import com.autoalbum.ui.theme.AutoAlbumTheme

/**
 * Minimal main activity for AutoAlbum.
 *
 * Workflow:
 * 1. User taps START.
 * 2. Activity verifies overlay permission (SYSTEM_ALERT_WINDOW) and Accessibility service.
 * 3. Launches FloatingControlService, initiates automation, and minimizes to background.
 * 4. User opens their gallery app to start sequential playback.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AutoAlbumTheme {
                HomeScreen(
                    onLaunchController = { handleLaunchFloatingController() }
                )
            }
        }
    }

    private fun handleLaunchFloatingController() {
        // Step 1: Ensure Overlay permission
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please enable 'Display over other apps' to show the floating controller", Toast.LENGTH_LONG).show()
            val overlayIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(overlayIntent)
            return
        }

        // Step 2: Ensure Accessibility Service is enabled
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Please enable 'AutoAlbum' in Accessibility settings for gallery automation", Toast.LENGTH_LONG).show()
            val accessibilityIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(accessibilityIntent)
            return
        }

        // Step 3: Launch Floating Controller Service (Singleton)
        // Requirement E: LAUNCH starts the floating controller and minimizes app WITHOUT activating automation
        FloatingControlService.start(this)

        // Step 4: Minimize main application
        moveTaskToBack(true)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        // 1. Authoritative check via Settings.Secure
        try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (!enabledServices.isNullOrEmpty()) {
                val expectedFull = "$packageName/${AutoAlbumAccessibilityService::class.java.name}"
                val expectedShort = "$packageName/.automation.AutoAlbumAccessibilityService"
                val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
                colonSplitter.setString(enabledServices)
                while (colonSplitter.hasNext()) {
                    val componentName = colonSplitter.next()
                    if (componentName.equals(expectedFull, ignoreCase = true) ||
                        componentName.equals(expectedShort, ignoreCase = true)
                    ) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback to AccessibilityManager
        }

        // 2. Fallback check via AccessibilityManager enabled list
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val expectedServiceName = AutoAlbumAccessibilityService::class.java.name
        return enabledServices.any {
            it.resolveInfo?.serviceInfo?.packageName == packageName &&
            it.resolveInfo?.serviceInfo?.name == expectedServiceName
        }
    }
}
