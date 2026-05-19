package com.flowdroid.common

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FlowDroidExceptionTest {

    @Test fun `PermissionMissing has stable code and includes permission`() {
        val e = FlowDroidException.PermissionMissing("android.permission.POST_NOTIFICATIONS")
        assertThat(e.code).isEqualTo("PERM_MISSING")
        assertThat(e.message).contains("POST_NOTIFICATIONS")
        assertThat(e.userMessage).contains("POST_NOTIFICATIONS")
    }

    @Test fun `SystemService includes service name`() {
        val cause = RuntimeException("backing service died")
        val e = FlowDroidException.SystemService("NotificationManager", "notify failed", cause)
        assertThat(e.code).isEqualTo("SYS_SERVICE")
        assertThat(e.message).contains("NotificationManager")
        assertThat(e.message).contains("notify failed")
        assertThat(e.cause).isSameInstanceAs(cause)
    }

    @Test fun `Persistence carries cause`() {
        val sql = IllegalStateException("database closed")
        val e = FlowDroidException.Persistence("read failed", sql)
        assertThat(e.code).isEqualTo("PERSIST")
        assertThat(e.cause).isSameInstanceAs(sql)
    }

    @Test fun `Engine includes flowId for debugging`() {
        val e = FlowDroidException.Engine(flowId = "flow-123", message = "bad node")
        assertThat(e.code).isEqualTo("ENGINE")
        assertThat(e.message).contains("flow-123")
    }

    @Test fun `every subclass extends FlowDroidException`() {
        val classes = listOf(
            FlowDroidException.Configuration("x"),
            FlowDroidException.PermissionMissing("x"),
            FlowDroidException.SystemService("x", "y"),
            FlowDroidException.Persistence("x"),
            FlowDroidException.ServiceNotRunning("x"),
            FlowDroidException.Engine("f", "m"),
            FlowDroidException.Unexpected("x"),
        )
        classes.forEach { assertThat(it).isInstanceOf(FlowDroidException::class.java) }
    }
}
