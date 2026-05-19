package com.flowdroid.permission

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.flowdroid.common.StructuredLogger
import com.flowdroid.common.permission.DeepLinkTarget
import com.flowdroid.common.permission.OemBrand
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class OemBatteryHelperImplTest {

    private lateinit var context: Context
    private lateinit var logger: StructuredLogger
    private lateinit var helper: OemBatteryHelperImpl

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        logger = mockk(relaxed = true)
        helper = OemBatteryHelperImpl(context, logger)
    }

    private fun setManufacturer(name: String) {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", name)
    }

    // --------------------------------------------------------------------------- brand detection

    @Test
    fun `detect maps samsung manufacturer to SAMSUNG`() {
        setManufacturer("samsung")
        assertThat(helper.detect()).isEqualTo(OemBrand.SAMSUNG)
    }

    @Test
    fun `detect maps Samsung mixed-case manufacturer to SAMSUNG`() {
        setManufacturer("Samsung")
        assertThat(helper.detect()).isEqualTo(OemBrand.SAMSUNG)
    }

    @Test
    fun `detect maps xiaomi manufacturer to XIAOMI`() {
        setManufacturer("Xiaomi")
        assertThat(helper.detect()).isEqualTo(OemBrand.XIAOMI)
    }

    @Test
    fun `detect maps redmi alias to XIAOMI`() {
        setManufacturer("Redmi")
        assertThat(helper.detect()).isEqualTo(OemBrand.XIAOMI)
    }

    @Test
    fun `detect maps poco alias to XIAOMI`() {
        setManufacturer("POCO")
        assertThat(helper.detect()).isEqualTo(OemBrand.XIAOMI)
    }

    @Test
    fun `detect maps oppo to OPPO`() {
        setManufacturer("OPPO")
        assertThat(helper.detect()).isEqualTo(OemBrand.OPPO)
    }

    @Test
    fun `detect maps realme to REALME`() {
        setManufacturer("realme")
        assertThat(helper.detect()).isEqualTo(OemBrand.REALME)
    }

    @Test
    fun `detect maps oneplus to ONEPLUS`() {
        setManufacturer("OnePlus")
        assertThat(helper.detect()).isEqualTo(OemBrand.ONEPLUS)
    }

    @Test
    fun `detect maps huawei to HUAWEI`() {
        setManufacturer("HUAWEI")
        assertThat(helper.detect()).isEqualTo(OemBrand.HUAWEI)
    }

    @Test
    fun `detect maps honor alias to HUAWEI`() {
        setManufacturer("HONOR")
        assertThat(helper.detect()).isEqualTo(OemBrand.HUAWEI)
    }

    @Test
    fun `detect maps vivo to VIVO`() {
        setManufacturer("vivo")
        assertThat(helper.detect()).isEqualTo(OemBrand.VIVO)
    }

    @Test
    fun `detect maps unknown manufacturer to GENERIC`() {
        setManufacturer("Acme")
        assertThat(helper.detect()).isEqualTo(OemBrand.GENERIC)
    }

    @Test
    fun `detect maps empty manufacturer to GENERIC`() {
        setManufacturer("")
        assertThat(helper.detect()).isEqualTo(OemBrand.GENERIC)
    }

    // ----------------------------------------------------------------- never throws across matrix

    @Test
    fun `resolveDeepLink never throws for every brand x target combination`() {
        for (brand in OemBrand.entries) {
            setManufacturer(brand.toManufacturerString())
            for (target in DeepLinkTarget.entries) {
                // Just assert no exception. Result may be Intent or null — both are valid.
                helper.resolveDeepLink(target)
            }
        }
    }

    private fun OemBrand.toManufacturerString(): String = when (this) {
        OemBrand.SAMSUNG -> "samsung"
        OemBrand.XIAOMI -> "xiaomi"
        OemBrand.OPPO -> "oppo"
        OemBrand.REALME -> "realme"
        OemBrand.ONEPLUS -> "oneplus"
        OemBrand.HUAWEI -> "huawei"
        OemBrand.VIVO -> "vivo"
        OemBrand.GENERIC -> "acme"
    }

    // ----------------------------------------------------------------- specific intent assertions

    @Test
    fun `BATTERY_OPTIMISATION returns intent with correct action and package URI`() {
        setManufacturer("generic")
        val intent = helper.resolveDeepLink(DeepLinkTarget.BATTERY_OPTIMISATION)
        assertThat(intent).isNotNull()
        // Either direct request or settings fallback — both are acceptable, but in the
        // direct-request path we get the package URI.
        if (intent!!.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) {
            assertThat(intent.data.toString()).startsWith("package:")
            assertThat(intent.data.toString()).contains(context.packageName)
        } else {
            assertThat(intent.action).isEqualTo(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    }

    @Test
    fun `NOTIFICATION_LISTENER_SETTINGS returns AOSP action`() {
        setManufacturer("samsung")
        val intent = helper.resolveDeepLink(DeepLinkTarget.NOTIFICATION_LISTENER_SETTINGS)
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    }

    @Test
    fun `ACCESSIBILITY_SETTINGS returns AOSP action`() {
        setManufacturer("generic")
        val intent = helper.resolveDeepLink(DeepLinkTarget.ACCESSIBILITY_SETTINGS)
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    @Test
    fun `APP_INFO returns intent with package URI`() {
        setManufacturer("generic")
        val intent = helper.resolveDeepLink(DeepLinkTarget.APP_INFO)
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        assertThat(intent.data.toString()).isEqualTo("package:${context.packageName}")
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    }

    @Test
    fun `POST_NOTIFICATIONS_RUNTIME returns null`() {
        setManufacturer("generic")
        val intent = helper.resolveDeepLink(DeepLinkTarget.POST_NOTIFICATIONS_RUNTIME)
        assertThat(intent).isNull()
    }

    @Test
    fun `OEM_NEVER_SLEEPING_APPS on Samsung returns either Samsung component or APP_INFO fallback`() {
        setManufacturer("samsung")
        val intent = helper.resolveDeepLink(DeepLinkTarget.OEM_NEVER_SLEEPING_APPS)
        assertThat(intent).isNotNull()
        // Either the Samsung component (when the package is shadowed by Robolectric as present)
        // or the APP_INFO fallback intent — both are valid.
        val isSamsungComponent = intent!!.component?.packageName == "com.samsung.android.lool"
        val isAppInfoFallback = intent.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        assertThat(isSamsungComponent || isAppInfoFallback).isTrue()
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    }

    @Test
    fun `OEM_AUTOSTART on Xiaomi returns either MIUI component or APP_INFO fallback`() {
        setManufacturer("xiaomi")
        val intent = helper.resolveDeepLink(DeepLinkTarget.OEM_AUTOSTART)
        assertThat(intent).isNotNull()
        val isMiuiComponent = intent!!.component?.packageName == "com.miui.securitycenter"
        val isAppInfoFallback = intent.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        assertThat(isMiuiComponent || isAppInfoFallback).isTrue()
    }

    @Test
    fun `OEM_AUTOSTART on generic device falls back to APP_INFO`() {
        setManufacturer("acme")
        val intent = helper.resolveDeepLink(DeepLinkTarget.OEM_AUTOSTART)
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    }

    @Test
    fun `EXACT_ALARM_PERMISSION on API 31+ returns intent with package URI`() {
        setManufacturer("generic")
        val intent = helper.resolveDeepLink(DeepLinkTarget.EXACT_ALARM_PERMISSION)
        // On API 34 in test config we expect either the request page or the APP_INFO fallback.
        assertThat(intent).isNotNull()
        assertThat(intent!!.data.toString()).startsWith("package:")
    }
}
