package com.flowdroid.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowdroid.common.Clock
import com.flowdroid.common.domain.LogEntry
import com.flowdroid.common.domain.NotificationEvent
import com.flowdroid.common.repo.LogRepository
import com.flowdroid.common.repo.NotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * Drives the two tabs of the Log screen.
 *
 *  - `notifications`: cold flow from [NotificationRepository], filtered by [NotificationFilter]
 *    (time window) AND by a free-text query against package/title/text.
 *  - `appLog`: cold flow from [LogRepository], filtered by minimum [LogEntry.Level] AND by a
 *    free-text query against tag/message/fields.
 *
 * Filter changes are stored in their own [MutableStateFlow] so flipping a chip / typing into the
 * search box doesn't reorder the list mid-scroll — both sources are combined and only the
 * visible projection changes.
 */
@HiltViewModel
class LogViewModel @Inject constructor(
    private val notificationRepository: NotificationRepository,
    private val logRepository: LogRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _notificationFilter = MutableStateFlow(NotificationFilter.All)
    val notificationFilter: StateFlow<NotificationFilter> = _notificationFilter.asStateFlow()

    private val _logFilter = MutableStateFlow(LogLevelFilter.AllLevels)
    val logFilter: StateFlow<LogLevelFilter> = _logFilter.asStateFlow()

    private val _notificationQuery = MutableStateFlow("")
    val notificationQuery: StateFlow<String> = _notificationQuery.asStateFlow()

    private val _logQuery = MutableStateFlow("")
    val logQuery: StateFlow<String> = _logQuery.asStateFlow()

    val notifications: StateFlow<List<NotificationEvent>> = combine(
        notificationRepository.observeRecent(limit = 200),
        _notificationFilter,
        _notificationQuery,
    ) { events, filter, query ->
        val sinceCutoff = filter.cutoffMillis(clock.nowMillis())
        val timeFiltered = if (sinceCutoff == null) events
            else events.filter { it.postTimeMillis >= sinceCutoff }
        applyNotificationQuery(timeFiltered, query)
    }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val appLog: StateFlow<List<LogEntry>> = combine(
        _logFilter.flatMapLatest { filter ->
            logRepository.observeRecent(limit = 500, minLevel = filter.minLevel)
                .catch { emit(emptyList()) }
        },
        _logQuery,
    ) { entries, query -> applyLogQuery(entries, query) }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setNotificationFilter(filter: NotificationFilter) {
        _notificationFilter.value = filter
    }

    fun setLogFilter(filter: LogLevelFilter) {
        _logFilter.value = filter
    }

    fun setNotificationQuery(query: String) {
        _notificationQuery.value = query
    }

    fun setLogQuery(query: String) {
        _logQuery.value = query
    }

    /**
     * Plain-text export of the CURRENTLY-FILTERED notification list — newest first, one line per
     * notification with the fields most useful for offline debugging. Returns the raw bytes so
     * the caller (UI layer) can write to a SAF document URI.
     */
    fun exportNotificationsText(): String {
        val rows = notifications.value
        return buildString {
            append("# FlowDroid notifications export — ${rows.size} rows\n")
            rows.forEach { e ->
                append(e.postTimeMillis).append('\t')
                append(e.packageName).append('\t')
                append(e.title.orEmpty().replace('\t', ' ')).append('\t')
                append(e.text.orEmpty().replace('\t', ' ').replace('\n', ' ')).append('\t')
                append("channel=").append(e.channelId.orEmpty()).append('\t')
                append("ongoing=").append(e.isOngoing).append('\t')
                append("actions=").append(e.actionLabels.joinToString(",").replace('\t', ' '))
                append('\n')
            }
        }
    }

    /**
     * Plain-text export of the CURRENTLY-FILTERED app log. Each row is tab-separated so the file
     * pastes cleanly into a spreadsheet; stack traces are inlined on the same row with the
     * literal `\n` between lines preserved.
     */
    fun exportLogText(): String {
        val rows = appLog.value
        return buildString {
            append("# FlowDroid app log export — ${rows.size} rows\n")
            rows.forEach { e ->
                append(e.timestampMillis).append('\t')
                append(e.level.name).append('\t')
                append(e.tag).append('\t')
                append(e.message.replace('\t', ' ').replace('\n', ' ')).append('\t')
                append("fields=").append(e.fieldsJson.orEmpty().replace('\t', ' ').replace('\n', ' '))
                e.throwableClass?.let { tc ->
                    append('\t').append("throwable=").append(tc).append(": ")
                        .append(e.throwableMessage.orEmpty().replace('\t', ' ').replace('\n', ' '))
                }
                e.stackTraceFirstLines?.let {
                    append('\t').append("stack=").append(it.replace('\t', ' ').replace('\n', ' '))
                }
                append('\n')
            }
        }
    }

    private fun applyNotificationQuery(events: List<NotificationEvent>, query: String): List<NotificationEvent> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return events
        return events.filter { e ->
            e.packageName.lowercase().contains(q) ||
                e.title.orEmpty().lowercase().contains(q) ||
                e.text.orEmpty().lowercase().contains(q) ||
                e.bigText.orEmpty().lowercase().contains(q) ||
                e.channelId.orEmpty().lowercase().contains(q) ||
                e.actionLabels.any { it.lowercase().contains(q) }
        }
    }

    private fun applyLogQuery(entries: List<LogEntry>, query: String): List<LogEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return entries
        return entries.filter { e ->
            e.tag.lowercase().contains(q) ||
                e.message.lowercase().contains(q) ||
                e.fieldsJson.orEmpty().lowercase().contains(q) ||
                e.throwableClass.orEmpty().lowercase().contains(q) ||
                e.throwableMessage.orEmpty().lowercase().contains(q)
        }
    }
}

/** Filter for the Notifications tab. */
enum class NotificationFilter(val label: String) {
    All("All"),
    LastHour("Last hour"),
    LastDay("Last day");

    fun cutoffMillis(now: Long): Long? = when (this) {
        All -> null
        LastHour -> now - 60 * 60 * 1_000L
        LastDay -> now - 24 * 60 * 60 * 1_000L
    }
}

/** Filter for the App log tab — chosen minimum level. */
enum class LogLevelFilter(val label: String, val minLevel: LogEntry.Level) {
    AllLevels("All", LogEntry.Level.DEBUG),
    InfoPlus("Info+", LogEntry.Level.INFO),
    WarnPlus("Warn+", LogEntry.Level.WARN),
    ErrorOnly("Error", LogEntry.Level.ERROR),
}
