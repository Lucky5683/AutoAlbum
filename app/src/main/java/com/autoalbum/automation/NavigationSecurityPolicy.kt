package com.autoalbum.automation

import com.autoalbum.automation.gallery.GalleryRegistry

/**
 * Enforces mandatory security policies before any accessibility inspection,
 * gesture dispatch, or action click.
 *
 * Security Requirements:
 * - Never dispatch a swipe or click without revalidating active application.
 * - Active package must belong to GalleryRegistry allowlist.
 * - Active package must match the expected gallery package.
 * - State must strictly be RUNNING.
 * - Abort immediately if root window is null or package cannot be verified.
 * - Cancel pending navigation on window switch to non-gallery applications (WhatsApp, Settings, etc.).
 * - Cancel pending navigation when STOP, PAUSE, or Screen Lock occurs.
 */
object NavigationSecurityPolicy {

    sealed class ValidationResult {
        object Allowed : ValidationResult()
        data class Rejected(val reason: String) : ValidationResult()
    }

    /**
     * Determines whether an incoming accessibility event package requires
     * cancelling any pending delayed navigation.
     * Returns true if the package is unsupported, or if automation is not in RUNNING state.
     */
    fun shouldCancelPendingOnEvent(eventPackage: String?, currentState: AutomationCoordinator.State): Boolean {
        if (currentState != AutomationCoordinator.State.RUNNING) {
            return true
        }
        if (eventPackage.isNullOrEmpty()) {
            return false
        }
        return !GalleryRegistry.isSupportedPackage(eventPackage)
    }

    /**
     * Revalidates environment immediately prior to evaluating navigation options
     * (e.g. after the 400ms or 600ms advance delay).
     */
    fun validatePreNavigation(
        currentState: AutomationCoordinator.State,
        isRootNull: Boolean,
        rootPackage: String?
    ): ValidationResult {
        if (currentState != AutomationCoordinator.State.RUNNING) {
            return ValidationResult.Rejected("Automation is not in RUNNING state (current: $currentState)")
        }
        if (isRootNull || rootPackage == null) {
            return ValidationResult.Rejected("Root window is null or unidentifiable")
        }
        if (!GalleryRegistry.isSupportedPackage(rootPackage)) {
            return ValidationResult.Rejected("Active package '$rootPackage' is not a supported gallery")
        }
        return ValidationResult.Allowed
    }

    /**
     * Revalidates environment immediately prior to dispatching ACTION_CLICK or dispatchGesture().
     * Enforces that root window still exists, matches the expected gallery package,
     * and is in GalleryRegistry allowlist.
     */
    fun validatePreDispatch(
        currentState: AutomationCoordinator.State,
        expectedPackage: String,
        isRootNull: Boolean,
        currentRootPackage: String?
    ): ValidationResult {
        if (currentState != AutomationCoordinator.State.RUNNING) {
            return ValidationResult.Rejected("Automation is not in RUNNING state (current: $currentState)")
        }
        if (isRootNull || currentRootPackage == null) {
            return ValidationResult.Rejected("Root window is null immediately prior to dispatch")
        }
        if (currentRootPackage != expectedPackage) {
            return ValidationResult.Rejected("Window package changed: expected '$expectedPackage' but active is '$currentRootPackage'")
        }
        if (!GalleryRegistry.isSupportedPackage(currentRootPackage)) {
            return ValidationResult.Rejected("Active package '$currentRootPackage' is not allowlisted")
        }
        return ValidationResult.Allowed
    }
}
