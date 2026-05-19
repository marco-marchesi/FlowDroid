package com.flowdroid.service

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity

/**
 * Transient transparent activity used to dismiss the keyguard before dispatching UI gestures.
 *
 * There is no public Android API to silently dismiss the keyguard from a background context. The
 * supported pattern is:
 *  1. Launch an activity that is marked `setShowWhenLocked(true)` + `setTurnScreenOn(true)`.
 *  2. From that activity call [KeyguardManager.requestDismissKeyguard].
 *  3. If the device is unsecured (swipe-only lock), it dismisses immediately and our callback
 *     fires `onDismissSucceeded`. If the device is secured (PIN/pattern/biometric), the system
 *     shows its unlock prompt and the callback fires after the user authenticates.
 *
 * Result is published via [UnlockBridge] so the caller (the `AccessibilityController`) can await
 * completion from a coroutine. The activity finishes itself in every branch.
 *
 * Caveats:
 *  - **Background activity launches** are restricted on Android 10+. The
 *    [com.flowdroid.service.FlowDroidNotificationListenerService] has a special grant that lets us
 *    start activities while processing a notification — that's the chain used by notification-driven
 *    flows. Outside of that grant (e.g. from a `WorkManager` job), launching this activity may
 *    fail silently; we still emit a typed error.
 *  - **Samsung One UI** is more restrictive than AOSP. Behavior on a secured A53 is documented
 *    in the README's "Lock-screen behaviour" section; one-off auth prompts work, but a device
 *    sitting idle for >5 min may refuse the background activity until the user wakes it.
 */
class UnlockHelperActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over the lock screen and wake the display.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }

        val km = getSystemService(KeyguardManager::class.java)
        if (km == null) {
            UnlockBridge.complete(success = false, reason = "no KeyguardManager")
            finishImmediately()
            return
        }

        if (!km.isKeyguardLocked) {
            // Race: keyguard already dismissed by the time we got here.
            UnlockBridge.complete(success = true, reason = "already unlocked")
            finishImmediately()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                km.requestDismissKeyguard(
                    this,
                    object : KeyguardManager.KeyguardDismissCallback() {
                        override fun onDismissError() {
                            UnlockBridge.complete(success = false, reason = "dismiss error")
                            finishImmediately()
                        }
                        override fun onDismissSucceeded() {
                            UnlockBridge.complete(success = true, reason = "dismiss succeeded")
                            finishImmediately()
                        }
                        override fun onDismissCancelled() {
                            UnlockBridge.complete(success = false, reason = "user cancelled")
                            finishImmediately()
                        }
                    },
                )
            } catch (t: Throwable) {
                if (t is OutOfMemoryError) throw t
                UnlockBridge.complete(success = false, reason = "requestDismissKeyguard threw: ${t.message}")
                finishImmediately()
            }
        } else {
            // Pre-Oreo: the FLAG_DISMISS_KEYGUARD window flag does the dismissal. We can't await
            // the result; assume success for swipe locks. (Min SDK is 29, so this branch is dead;
            // kept for documentation.)
            UnlockBridge.complete(success = true, reason = "pre-O flag dismissal")
            finishImmediately()
        }
    }

    private fun finishImmediately() {
        // Don't keep the empty activity in recents.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            finish()
        }
    }
}
