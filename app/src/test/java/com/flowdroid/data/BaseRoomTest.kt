package com.flowdroid.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.flowdroid.common.FakeClock
import com.flowdroid.common.StructuredLogger
import com.flowdroid.data.db.FlowDroidDatabase
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Base for Robolectric-backed Room tests.
 *
 * - Boots a real Robolectric `Application` so we can resolve `Context`.
 * - Builds an in-memory [FlowDroidDatabase] per test, with main-thread queries allowed (Robolectric
 *   runs everything on the main thread).
 * - Provides a fake [StructuredLogger] and a [FakeClock] so production code does not call out to
 *   Timber or `System.currentTimeMillis()` during tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
abstract class BaseRoomTest {

    protected lateinit var db: FlowDroidDatabase
    protected lateinit var clock: FakeClock
    protected lateinit var logger: RecordingLogger

    @Before
    fun setUpDatabase() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, FlowDroidDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        clock = FakeClock(wallMillis = 1_000_000L, elapsedMillis = 0L)
        logger = RecordingLogger()
    }

    @After
    fun tearDownDatabase() {
        db.close()
    }
}

/**
 * Minimal in-memory [StructuredLogger] that records calls. Tests can inspect [warnings] to
 * verify that repositories actually log on failure paths.
 */
class RecordingLogger : StructuredLogger {

    data class Entry(
        val level: String,
        val tag: String,
        val message: String,
        val throwable: Throwable?,
        val fields: Map<String, Any?>,
    )

    private val _entries: MutableList<Entry> = mutableListOf()
    val entries: List<Entry> get() = _entries.toList()
    val warnings: List<Entry> get() = _entries.filter { it.level == "WARN" }

    override fun debug(tag: String, message: String, vararg fields: Pair<String, Any?>) {
        _entries += Entry("DEBUG", tag, message, null, fields.toMap())
    }

    override fun info(tag: String, message: String, vararg fields: Pair<String, Any?>) {
        _entries += Entry("INFO", tag, message, null, fields.toMap())
    }

    override fun warn(
        tag: String,
        message: String,
        throwable: Throwable?,
        vararg fields: Pair<String, Any?>,
    ) {
        _entries += Entry("WARN", tag, message, throwable, fields.toMap())
    }

    override fun error(
        tag: String,
        message: String,
        throwable: Throwable?,
        vararg fields: Pair<String, Any?>,
    ) {
        _entries += Entry("ERROR", tag, message, throwable, fields.toMap())
    }
}
