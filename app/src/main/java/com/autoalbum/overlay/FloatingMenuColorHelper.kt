package com.autoalbum.overlay

import com.autoalbum.automation.AutomationCoordinator

enum class MenuButtonType {
    START,
    PAUSE,
    STOP
}

/**
 * Manages floating controller button appearance and state synchronization.
 *
 * Requirements:
 * - START, PAUSE and STOP must have the same neutral default background and appearance.
 * - When automation is RUNNING, highlight only START using light red.
 * - When automation is PAUSED, highlight only PAUSE using light red.
 * - When automation is STOPPED or inactive (IDLE), no button should be highlighted.
 * - All controls reflect actual AutomationCoordinator state.
 */
object FloatingMenuColorHelper {

    // Neutral dark slate background for inactive/unhighlighted buttons
    const val COLOR_NEUTRAL_BG = "#263246"

    // Light red highlight color
    const val COLOR_LIGHT_RED_HIGHLIGHT = "#EF4444"

    // Text color
    const val COLOR_TEXT_WHITE = "#FFFFFF"

    fun getButtonBackgroundColor(
        buttonType: MenuButtonType,
        state: AutomationCoordinator.State
    ): String {
        return when (state) {
            AutomationCoordinator.State.RUNNING -> {
                if (buttonType == MenuButtonType.START) COLOR_LIGHT_RED_HIGHLIGHT else COLOR_NEUTRAL_BG
            }
            AutomationCoordinator.State.PAUSED -> {
                if (buttonType == MenuButtonType.PAUSE) COLOR_LIGHT_RED_HIGHLIGHT else COLOR_NEUTRAL_BG
            }
            AutomationCoordinator.State.STOPPED,
            AutomationCoordinator.State.IDLE -> {
                COLOR_NEUTRAL_BG
            }
        }
    }
}
