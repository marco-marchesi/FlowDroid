package com.flowdroid.data.flows

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.flowdroid.common.FakeClock
import com.flowdroid.common.Outcome
import com.flowdroid.common.TimberStructuredLogger
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies every bundled template parses through [FlowImportExport] without errors. Catches
 * three categories of regression:
 *  - JSON typo / unknown discriminator in a template asset
 *  - Asset missing from the APK (e.g. file referenced by [TemplateGallery] but not packaged)
 *  - Field renames in the surrogate model that an older template hasn't followed
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class TemplateGalleryTest {

    private lateinit var context: Context
    private lateinit var gallery: TemplateGallery
    private val clock = FakeClock(wallMillis = 1_700_000_000_000L)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        gallery = TemplateGallery(context, TimberStructuredLogger())
    }

    @Test fun `gallery exposes at least 10 templates with unique IDs`() {
        assertThat(gallery.templates.size).isAtLeast(10)
        val ids = gallery.templates.map { it.id }
        assertThat(ids).containsNoDuplicates()
        gallery.templates.forEach { t ->
            assertThat(t.title).isNotEmpty()
            assertThat(t.description).isNotEmpty()
            assertThat(t.asset).startsWith("templates/")
            assertThat(t.asset).endsWith(".json")
        }
    }

    @Test fun `every template asset is packaged and readable`() {
        gallery.templates.forEach { t ->
            val json = gallery.load(t)
            assertThat(json).isNotNull()
            assertThat(json!!.length).isGreaterThan(50)
        }
    }

    @Test fun `every template imports to exactly one valid disabled flow`() {
        gallery.templates.forEach { t ->
            val json = gallery.load(t) ?: error("template ${t.id} missing")
            val outcome = FlowImportExport.importFlows(json, clock)
            check(outcome is Outcome.Ok) {
                "template ${t.id} failed to import: $outcome"
            }
            val flows = outcome.value
            assertThat(flows).hasSize(1)
            val flow = flows[0]
            assertThat(flow.enabled).isFalse()  // FlowImportExport forces this
            assertThat(flow.name).isNotEmpty()
            assertThat(flow.triggers).isNotEmpty()
        }
    }
}
