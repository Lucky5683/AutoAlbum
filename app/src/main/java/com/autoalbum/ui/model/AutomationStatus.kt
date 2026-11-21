package com.autoalbum.ui.model

enum class AutomationStatus(
    val label: String,
    val description: String
) {
    IDLE(
        label = "Idle",
        description = "Automation is in standby. Press Start Automation to begin."
    ),
    RUNNING(
        label = "Running",
        description = "Automation session is running (UI simulation mode). Press Stop to halt."
    ),
    STOPPED(
        label = "Stopped",
        description = "Automation session has stopped. Press Start Automation to resume."
    )
}

