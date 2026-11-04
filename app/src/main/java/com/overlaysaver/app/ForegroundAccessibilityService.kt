package com.overlaysaver.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class ForegroundAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var currentForegroundPackage: String? = null
            private set
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            currentForegroundPackage = event.packageName?.toString()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        currentForegroundPackage = null
    }
}
