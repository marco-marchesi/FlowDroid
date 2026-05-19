package com.flowdroid.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Single Room database for FlowDroid's data layer.
 *
 * Contains four tables (notifications, log entries, health events, flows). All bookkeeping the
 * data layer needs lives here.
 *
 * Schema export is on (`exportSchema = true`) — JSON schema files end up in `schemas/` and are
 * the source of truth for migrations.
 *
 * Versions:
 *  - v1: notifications, log_entries, health_events.
 *  - v2: adds `flows` table (Phase 1 MVP). See [MIGRATION_1_2].
 *  - v3: renames `flows.triggerJson` → `flows.triggersJson` and wraps every existing single
 *        trigger object as a one-element JSON array, matching the Phase 2 multi-trigger domain
 *        shape. See [MIGRATION_2_3].
 *  - v4: adds `flow_runs` table for per-flow execution history. Pure CREATE — additive, no
 *        existing data touched. See [MIGRATION_3_4].
 */
@Database(
    entities = [
        NotificationEventEntity::class,
        LogEntryEntity::class,
        HealthEventEntity::class,
        FlowEntity::class,
        FlowRunEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class FlowDroidDatabase : RoomDatabase() {

    /** DAO for [NotificationEventEntity]. Bound by Hilt module. */
    abstract fun notificationEventDao(): NotificationEventDao

    /** DAO for [LogEntryEntity]. Bound by Hilt module. */
    abstract fun logEntryDao(): LogEntryDao

    /** DAO for [HealthEventEntity]. Bound by Hilt module. */
    abstract fun healthEventDao(): HealthEventDao

    /** DAO for [FlowEntity]. Bound by Hilt module. */
    abstract fun flowDao(): FlowDao

    /** DAO for [FlowRunEntity]. Bound by Hilt module. */
    abstract fun flowRunDao(): FlowRunDao

    companion object {
        /** On-disk database name. Stable across versions. */
        const val NAME: String = "flowdroid.db"

        /**
         * v1 → v2: adds the `flows` table backing [FlowEntity] plus its `enabled` index.
         *
         * The CREATE TABLE statement must match the schema Room generates for [FlowEntity]; if
         * they diverge, Room's `validateMigration` check on the next open will throw and the
         * mismatch is caught by the migration test, not in the field.
         *
         * Column types are SQLite affinities (`TEXT` for String, `INTEGER` for Long/Boolean).
         * Booleans are persisted as 0/1 in INTEGER columns — the same encoding Room uses.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `flows` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `triggerJson` TEXT NOT NULL,
                        `actionsJson` TEXT NOT NULL,
                        `notes` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_flow_enabled` ON `flows` (`enabled`)",
                )
            }
        }

        /**
         * v2 → v3: rename `flows.triggerJson` to `flows.triggersJson` and convert every value
         * from a single-trigger JSON object to a one-element JSON array.
         *
         * Wrap semantics
         * --------------
         * Phase 1 stored the single trigger as `{"type":"NotificationPosted", ...}`. Phase 2 widens
         * the domain to `List<Trigger>`, so the persisted form must be a JSON array. The wrap is
         * a pure text concatenation — `'[' || triggerJson || ']'` — because every legal v2 payload
         * is already a single JSON object, never an array. No JSON parsing is needed at migration
         * time; the column is opaque to SQLite and the new shape is validated by Room on first read
         * via the surrogate types.
         *
         * SQLite cannot rename a column in-place on its supported subset, so we use the standard
         * CREATE NEW + COPY + DROP OLD + RENAME pattern. The new table's column types and the
         * `idx_flow_enabled` index are reconstructed identically to the v2 schema so Room's
         * `validateMigration` check passes on first open.
         */
        /**
         * v3 → v4: adds `flow_runs` table for per-flow execution history (Phase 12).
         *
         * Pure additive — CREATE TABLE + two CREATE INDEX. No existing data is read or modified.
         * If the migration fails (disk full / corruption), Room raises and the user's existing
         * flows + logs + notifications are untouched, which is the desired blast radius.
         *
         * Indices match the entity's `@Index` declarations so Room's `validateMigration` check
         * on the next open passes.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `flow_runs` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `flowId` TEXT NOT NULL,
                        `flowName` TEXT NOT NULL,
                        `executionId` TEXT NOT NULL,
                        `triggerKind` TEXT NOT NULL,
                        `startedAtMillis` INTEGER NOT NULL,
                        `endedAtMillis` INTEGER NOT NULL,
                        `ok` INTEGER NOT NULL,
                        `okCount` INTEGER NOT NULL,
                        `errCount` INTEGER NOT NULL,
                        `aborted` INTEGER NOT NULL,
                        `errorMessage` TEXT,
                        `actionResultsJson` TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_flow_run_started` ON `flow_runs` (`startedAtMillis`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_flow_run_flowId` ON `flow_runs` (`flowId`)",
                )
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `flows_new` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `triggersJson` TEXT NOT NULL,
                        `actionsJson` TEXT NOT NULL,
                        `notes` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `flows_new`
                        (`id`, `name`, `enabled`, `triggersJson`, `actionsJson`, `notes`, `createdAt`, `updatedAt`)
                    SELECT
                        `id`, `name`, `enabled`,
                        '[' || `triggerJson` || ']',
                        `actionsJson`, `notes`, `createdAt`, `updatedAt`
                    FROM `flows`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `flows`")
                db.execSQL("ALTER TABLE `flows_new` RENAME TO `flows`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_flow_enabled` ON `flows` (`enabled`)",
                )
            }
        }
    }
}
