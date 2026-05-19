package com.flowdroid.data.repo

import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import com.flowdroid.common.repo.PersistError
import java.io.IOException

/**
 * Translate raw [Throwable]s thrown by Room / SQLite into typed [PersistError] cases.
 *
 * The mapping is intentionally narrow: anything we have not classified explicitly maps to
 * [PersistError.WriteFailed] (when it surfaces from an insert path) or [PersistError.ReadFailed]
 * (from a read path) — but since the discriminator (read vs write) is not available at this
 * level, we conservatively use [PersistError.WriteFailed] which the repository callers know to
 * interpret as "operation failed; reason attached".
 *
 * Special cases:
 *  - [SQLiteDatabaseCorruptException] → [PersistError.Corrupted]. Recovery requires a wipe; we
 *    surface this distinctly so higher layers can show a "database corrupted, please reinstall"
 *    UI instead of silently retrying.
 *  - [IllegalStateException] (typically "attempt to re-open an already-closed database") →
 *    [PersistError.DatabaseUnavailable]. Common during shutdown races.
 *  - [IOException] → [PersistError.DatabaseUnavailable]. The underlying file disappeared,
 *    permission revoked, etc.
 */
internal fun mapPersistError(t: Throwable): PersistError = when (t) {
    is SQLiteDatabaseCorruptException -> PersistError.Corrupted(t.message ?: t.javaClass.simpleName)
    is SQLiteException -> PersistError.WriteFailed(t.message ?: t.javaClass.simpleName)
    is IOException -> PersistError.DatabaseUnavailable
    is IllegalStateException -> PersistError.DatabaseUnavailable
    else -> PersistError.WriteFailed(t.message ?: t.javaClass.simpleName)
}
