package com.linux_core.xlauncher

/** What a still/held finger in MOUSE mode does. */
enum class HoldAction {
    /**
     * Default scheme: tap = click. A second touch-down landing soon enough
     * and close enough to a completed tap grabs the left button immediately
     * (no timer) — "click, click, don't let go the second time" — for
     * dragging. A plain hold with no prior tap right-clicks instead (after
     * [LauncherSettings.longPressMs], same as two fingers held still).
     * Two fingers dragged scrolls.
     */
    TAP_TAP_HOLD,

    /** Any single-finger hold grabs the left button for dragging. No tap-counting; right-click is two-finger-hold only. */
    GRAB_DRAG,

    /** Any single-finger hold right-clicks. No tap-counting, no drag-by-holding. */
    RIGHT_CLICK,
}
