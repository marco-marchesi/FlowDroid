package com.flowdroid.data.flows

import com.flowdroid.common.FakeClock
import com.flowdroid.common.Outcome
import com.flowdroid.common.flow.Action
import com.flowdroid.common.flow.Flow
import com.flowdroid.common.flow.Trigger
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FlowImportExportTest {

    private val clock = FakeClock(wallMillis = 1_700_000_000_000L)

    private fun sampleFlow(name: String = "S"): Flow = Flow(
        id = "ignored-on-export",
        name = name,
        enabled = true,
        triggers = listOf(Trigger.NotificationPosted(packageName = "com.example.app")),
        actions = listOf(
            Action.PostNotification(title = "hi", text = "{notification.title}"),
            Action.Delay(millis = 250L),
        ),
        notes = "demo",
        createdAt = 1L,
        updatedAt = 2L,
    )

    @Test fun `single flow round-trip preserves trigger and action shapes`() {
        val json = FlowImportExport.exportFlow(sampleFlow())
        val r = FlowImportExport.importFlows(json, clock)
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        val out = (r as Outcome.Ok).value
        assertThat(out).hasSize(1)
        val f = out[0]
        assertThat(f.name).isEqualTo("S")
        assertThat(f.enabled).isFalse()
        assertThat(f.triggers.single()).isEqualTo(Trigger.NotificationPosted(packageName = "com.example.app"))
        assertThat(f.actions).hasSize(2)
        assertThat(f.notes).isEqualTo("demo")
        assertThat(f.id).isNotEqualTo("ignored-on-export")
        assertThat(f.createdAt).isEqualTo(clock.nowMillis())
    }

    @Test fun `multi flow round-trip preserves order and count`() {
        val json = FlowImportExport.exportFlows(listOf(sampleFlow("A"), sampleFlow("B"), sampleFlow("C")))
        val r = FlowImportExport.importFlows(json, clock)
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        val out = (r as Outcome.Ok).value
        assertThat(out.map { it.name }).containsExactly("A", "B", "C").inOrder()
        assertThat(out.map { it.id }.toSet()).hasSize(3)
    }

    @Test fun `malformed json returns MalformedJson`() {
        val r = FlowImportExport.importFlows("{not valid", clock)
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error).isInstanceOf(ImportError.MalformedJson::class.java)
    }

    @Test fun `missing version is MalformedJson`() {
        val r = FlowImportExport.importFlows("""{"flow":{"name":"x","enabled":true,"triggers":[],"actions":[]}}""", clock)
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error).isInstanceOf(ImportError.MalformedJson::class.java)
    }

    @Test fun `unsupported version is UnsupportedVersion`() {
        val r = FlowImportExport.importFlows("""{"version":99,"flow":{"name":"x","enabled":true,"triggers":[],"actions":[]}}""", clock)
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        val err = (r as Outcome.Err).error
        assertThat(err).isInstanceOf(ImportError.UnsupportedVersion::class.java)
        assertThat((err as ImportError.UnsupportedVersion).version).isEqualTo(99)
    }

    @Test fun `empty flows array is Empty`() {
        val r = FlowImportExport.importFlows("""{"version":1,"flows":[]}""", clock)
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error).isEqualTo(ImportError.Empty)
    }

    @Test fun `payload with neither flow nor flows is MalformedJson`() {
        val r = FlowImportExport.importFlows("""{"version":1}""", clock)
        assertThat(r).isInstanceOf(Outcome.Err::class.java)
        assertThat((r as Outcome.Err).error).isInstanceOf(ImportError.MalformedJson::class.java)
    }
}
