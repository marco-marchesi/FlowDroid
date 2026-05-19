package com.flowdroid.service

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure-JVM sanity check on the retention constants. The full worker requires WorkManager + Hilt
 * and is exercised by the Robolectric integration suite — this test just guards against someone
 * accidentally dropping the 2-day target.
 */
class RetentionWorkerLogicTest {

    @Test fun `retention window is 2 days`() {
        assertThat(RetentionWorker.RETENTION_DAYS).isEqualTo(2L)
        assertThat(RetentionWorker.RETENTION_MILLIS).isEqualTo(2L * 24 * 60 * 60 * 1_000L)
    }

    @Test fun `cutoff at now equals now minus 2 days`() {
        val now = 1_700_000_000_000L
        val cutoff = now - RetentionWorker.RETENTION_MILLIS
        assertThat(now - cutoff).isEqualTo(172_800_000L)
    }
}
