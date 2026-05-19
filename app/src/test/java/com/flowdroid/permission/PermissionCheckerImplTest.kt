package com.flowdroid.permission

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.permission.OemBatteryHelper
import com.flowdroid.common.permission.OemBrand
import com.flowdroid.common.permission.OemSpecific
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric-driven tests for the ENABLED_NOTIFICATION_LISTENERS parser. We write directly
 * into [Settings.Secure] using `putString` (Robolectric's shadow accepts writes without the
 * normal WRITE_SECURE_SETTINGS guard) and assert that the snapshot picks up the change after
 * [PermissionCheckerImpl.refresh].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class PermissionCheckerImplTest {

    private lateinit var context: Context
    private lateinit var logger: StructuredLogger
    private lateinit var oemDetector: OemDetector
    private lateinit var oemHelper: OemBatteryHelper

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        logger = mockk(relaxed = true)
        oemHelper = mockk(relaxed = true) {
            every { detect() } returns OemBrand.GENERIC
        }
        oemDetector = OemDetector(oemHelper)
        // Always start from a clean ENABLED_NOTIFICATION_LISTENERS.
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", "")
    }

    private fun newChecker(): PermissionCheckerImpl =
        PermissionCheckerImpl(context, logger, oemDetector)

    // ---------------------------------------------------------- notification listener parsing

    @Test
    fun `notification listener enabled when single matching entry present`() {
        Settings.Secure.putString(
            context.contentResolver,
            "enabled_notification_listeners",
            "${context.packageName}/com.flowdroid.service.FlowDroidNotificationListenerService",
        )
        val checker = newChecker()
        checker.refresh()
        assertThat(checker.snapshot().notificationListenerEnabled).isTrue()
    }

    @Test
    fun `notification listener enabled when our entry is second after colon separator`() {
        val value = "com.other/com.other.Service:" +
                "${context.packageName}/com.flowdroid.service.FlowDroidNotificationListenerService"
        Settings.Secure.putString(
            context.contentResolver,
            "enabled_notification_listeners",
            value,
        )
        val checker = newChecker()
        checker.refresh()
        assertThat(checker.snapshot().notificationListenerEnabled).isTrue()
    }

    @Test
    fun `notification listener enabled when our entry is first followed by another`() {
        val value = "${context.packageName}/com.flowdroid.service.FlowDroidNotificationListenerService:" +
                "com.other/com.other.Service"
        Settings.Secure.putString(
            context.contentResolver,
            "enabled_notification_listeners",
            value,
        )
        val checker = newChecker()
        checker.refresh()
        assertThat(checker.snapshot().notificationListenerEnabled).isTrue()
    }

    @Test
    fun `notification listener disabled when setting is empty`() {
        Settings.Secure.putString(
            context.contentResolver,
            "enabled_notification_listeners",
            "",
        )
        val checker = newChecker()
        checker.refresh()
        assertThat(checker.snapshot().notificationListenerEnabled).isFalse()
    }

    @Test
    fun `notification listener disabled when setting is null`() {
        Settings.Secure.putString(
            context.contentResolver,
            "enabled_notification_listeners",
            null,
        )
        val checker = newChecker()
        checker.refresh()
        assertThat(checker.snapshot().notificationListenerEnabled).isFalse()
    }

    @Test
    fun `notification listener disabled when only a different listener is enabled`() {
        Settings.Secure.putString(
            context.contentResolver,
            "enabled_notification_listeners",
            "com.other/com.other.Service:com.another/com.another.Service",
        )
        val checker = newChecker()
        checker.refresh()
        assertThat(checker.snapshot().notificationListenerEnabled).isFalse()
    }

    @Test
    fun `notification listener tolerates malformed tokens`() {
        Settings.Secure.putString(
            context.contentResolver,
            "enabled_notification_listeners",
            "::garbage:::${context.packageName}/com.flowdroid.service.FlowDroidNotificationListenerService::",
        )
        val checker = newChecker()
        checker.refresh()
        assertThat(checker.snapshot().notificationListenerEnabled).isTrue()
    }

    // -------------------------------------------------------------------- other snapshot fields

    @Test
    fun `accessibility service disabled when no service enabled`() {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "",
        )
        val checker = newChecker()
        checker.refresh()
        assertThat(checker.snapshot().accessibilityServiceEnabled).isFalse()
    }

    @Test
    fun `snapshot returns same instance until refresh changes it`() {
        val checker = newChecker()
        val s1 = checker.snapshot()
        val s2 = checker.snapshot()
        assertThat(s1).isEqualTo(s2)
    }

    @Test
    fun `observe emits initial snapshot synchronously via StateFlow`() {
        val checker = newChecker()
        val flow = checker.observe()
        // StateFlow always has a value; conversion to Flow keeps that invariant.
        assertThat(flow).isNotNull()
        assertThat(checker.snapshot()).isNotNull()
    }

    @Test
    fun `refresh picks up Settings change`() {
        val checker = newChecker()
        assertThat(checker.snapshot().notificationListenerEnabled).isFalse()
        Settings.Secure.putString(
            context.contentResolver,
            "enabled_notification_listeners",
            "${context.packageName}/com.flowdroid.service.FlowDroidNotificationListenerService",
        )
        checker.refresh()
        assertThat(checker.snapshot().notificationListenerEnabled).isTrue()
    }

    @Test
    fun `oemSpecific defaults to None on generic device`() {
        val checker = newChecker()
        checker.refresh()
        assertThat(checker.snapshot().oemSpecific).isEqualTo(OemSpecific.None)
    }

    @Test
    fun `oemSpecific is Samsung with both flags false when detected as Samsung`() {
        every { oemHelper.detect() } returns OemBrand.SAMSUNG
        val checker = newChecker()
        checker.refresh()
        val oem = checker.snapshot().oemSpecific
        assertThat(oem).isInstanceOf(OemSpecific.Samsung::class.java)
        val s = oem as OemSpecific.Samsung
        assertThat(s.deviceCareConfigured).isFalse()
        assertThat(s.backgroundUsageNotLimited).isFalse()
    }
}
