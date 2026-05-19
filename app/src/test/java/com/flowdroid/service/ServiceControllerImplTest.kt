package com.flowdroid.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.flowdroid.common.StructuredLogger
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric required: ContextCompat.startForegroundService and Handler(Looper.getMainLooper())
// both branch on Build.VERSION.SDK_INT and on the presence of a real main Looper. Without
// Robolectric SDK_INT is 0 and Looper.getMainLooper() returns null, which makes the test
// observe the wrong API path.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ServiceControllerImplTest {

    private val logger: StructuredLogger = mockk(relaxed = true)
    private val channelManager: NotificationChannelManager = mockk(relaxed = true)
    private val packageManager: PackageManager = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true) {
        every { packageManager } returns this@ServiceControllerImplTest.packageManager
        every { packageName } returns "com.flowdroid"
    }

    private val controller = ServiceControllerImpl(context, channelManager, logger)

    @Test fun `bootstrap ensures channels and starts foreground service`() {
        // ContextCompat.startForegroundService dispatches to either context.startForegroundService
        // (API 26+) or context.startService on older. Min SDK is 29, so the FGS path is what we
        // expect. Capture the Intent and verify it targets FlowDroidForegroundService.
        val intentSlot = slot<Intent>()
        every { context.startForegroundService(capture(intentSlot)) } returns
            ComponentName(context, FlowDroidForegroundService::class.java)

        controller.bootstrap()

        verify(exactly = 1) { channelManager.ensureChannels(context) }
        verify(exactly = 1) { context.startForegroundService(any()) }
        assertThat(intentSlot.captured.component?.className)
            .isEqualTo(FlowDroidForegroundService::class.java.name)
    }

    @Test fun `toggleNotificationListener disables the component first`() {
        val componentSlot = slot<ComponentName>()
        val stateSlot = slot<Int>()
        val flagsSlot = slot<Int>()
        every {
            packageManager.setComponentEnabledSetting(
                capture(componentSlot),
                capture(stateSlot),
                capture(flagsSlot),
            )
        } returns Unit

        controller.toggleNotificationListener()

        verify {
            packageManager.setComponentEnabledSetting(
                any(),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        assertThat(componentSlot.captured.className)
            .isEqualTo(FlowDroidNotificationListenerService::class.java.name)
        assertThat(stateSlot.captured).isEqualTo(PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        assertThat(flagsSlot.captured).isEqualTo(PackageManager.DONT_KILL_APP)
    }

    @Test fun `shutdown stops the service`() {
        controller.shutdown()
        verify { context.stopService(any()) }
    }
}
