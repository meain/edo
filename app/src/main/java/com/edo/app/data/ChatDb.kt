package com.edo.app.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "workspace_uri") val workspaceUri: String,
    @ColumnInfo(name = "description", defaultValue = "") val description: String = "",
    @ColumnInfo(name = "yolo_mode", defaultValue = "0") val yoloMode: Boolean = false,
    /** Id of a saved [ModelProfile] to use for this project; null means "use the global setting". */
    @ColumnInfo(name = "profile_override") val profileOverride: Long? = null,
    @ColumnInfo(name = "max_turns_override") val maxTurnsOverride: Int? = null,
    /**
     * Opt-in: lets the `run_lua` tool make HTTP requests. Lua itself (compute plus read/write inside
     * this project's folder) is always on. The column keeps its original name so no migration is
     * needed.
     */
    @ColumnInfo(name = "lua_enabled", defaultValue = "0") val luaNetwork: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY created_at ASC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: Long): ProjectEntity?

    @Insert
    suspend fun insert(p: ProjectEntity): Long

    @Update
    suspend fun update(p: ProjectEntity)

    @Delete
    suspend fun delete(p: ProjectEntity)
}

@Entity(tableName = "threads")
data class ThreadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "project_id", index = true) val projectId: Long,
    @ColumnInfo(name = "title") val title: String = "New chat",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_updated_at") val lastUpdatedAt: Long = System.currentTimeMillis(),
    /** Running token and cost totals; see [UsageTotals]. */
    @ColumnInfo(name = "input_tokens", defaultValue = "0") val inputTokens: Long = 0,
    @ColumnInfo(name = "output_tokens", defaultValue = "0") val outputTokens: Long = 0,
    @ColumnInfo(name = "cost_usd", defaultValue = "0") val costUsd: Double = 0.0,
    @ColumnInfo(name = "cost_partial", defaultValue = "0") val costPartial: Boolean = false,
    /** Started by a schedule rather than by the user; the chat list marks these with an alarm icon. */
    @ColumnInfo(name = "from_schedule", defaultValue = "0") val fromSchedule: Boolean = false,
)

@Dao
interface ThreadDao {
    @Query("SELECT * FROM threads WHERE project_id = :projectId ORDER BY last_updated_at DESC")
    fun observeForProject(projectId: Long): Flow<List<ThreadEntity>>

    @Query("SELECT * FROM threads")
    fun observeAll(): Flow<List<ThreadEntity>>

    /** Chats whose title or any message contains [query] (case-insensitive for ASCII). */
    @Query(
        """
        SELECT * FROM threads WHERE project_id = :projectId AND (
            title LIKE '%' || :query || '%' ESCAPE '\'
            OR id IN (SELECT thread_id FROM messages WHERE content_json LIKE '%' || :query || '%' ESCAPE '\')
        ) ORDER BY last_updated_at DESC
        """
    )
    fun searchForProject(projectId: Long, query: String): Flow<List<ThreadEntity>>

    @Query("SELECT * FROM threads WHERE id = :id")
    suspend fun getById(id: Long): ThreadEntity?

    @Query("SELECT * FROM threads WHERE project_id = :projectId ORDER BY last_updated_at DESC LIMIT 1")
    suspend fun latestForProject(projectId: Long): ThreadEntity?

    @Insert
    suspend fun insert(t: ThreadEntity): Long

    @Update
    suspend fun update(t: ThreadEntity)

    @Delete
    suspend fun delete(t: ThreadEntity)

    @Query("DELETE FROM threads WHERE project_id = :projectId")
    suspend fun deleteForProject(projectId: Long)

    @Query("UPDATE threads SET title = :title, last_updated_at = :ts WHERE id = :id")
    suspend fun updateTitleAndTimestamp(id: Long, title: String, ts: Long)

    @Query("UPDATE threads SET last_updated_at = :ts WHERE id = :id")
    suspend fun touch(id: Long, ts: Long = System.currentTimeMillis())

    @Query(
        "UPDATE threads SET input_tokens = input_tokens + :inputTokens, output_tokens = output_tokens + :outputTokens, " +
            "cost_usd = cost_usd + :costUsd, cost_partial = MAX(cost_partial, :partial) WHERE id = :id"
    )
    suspend fun addUsage(id: Long, inputTokens: Long, outputTokens: Long, costUsd: Double, partial: Boolean)
}

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "thread_id", index = true) val threadId: Long,
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "content_json") val contentJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    /** Model that wrote an assistant message; blank for user messages and for rows from before v14. */
    @ColumnInfo(name = "model", defaultValue = "") val model: String = "",
    /** Provider that served an assistant message; blank for user messages and for rows from before v15. */
    @ColumnInfo(name = "provider", defaultValue = "") val provider: String = "",
)

/** A message's time, project and model: all the stats charts need from it. */
data class MessageStamp(
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "project_id") val projectId: Long,
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "model") val model: String,
    @ColumnInfo(name = "provider") val provider: String,
)

@Dao
interface MessageDao {
    @Query(
        "SELECT m.created_at, t.project_id, m.role, m.model, m.provider FROM messages m " +
            "JOIN threads t ON t.id = m.thread_id"
    )
    suspend fun stamps(): List<MessageStamp>

    @Query("SELECT * FROM messages WHERE thread_id = :threadId ORDER BY id ASC")
    suspend fun listForThread(threadId: Long): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE thread_id = :threadId")
    suspend fun countForThread(threadId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(msg: MessageEntity): Long

    /** Message timestamps since [since], for the stats page's activity chart. */
    @Query("SELECT created_at FROM messages WHERE created_at >= :since")
    suspend fun timestampsSince(since: Long): List<Long>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun countAll(): Int

    /** Assistant messages that called tools; the stats page counts the tool names in them. */
    @Query("SELECT content_json FROM messages WHERE role = 'Assistant' AND content_json LIKE '%\"tool_use\"%'")
    suspend fun toolCallJson(): List<String>

    @Query("DELETE FROM messages WHERE thread_id = :threadId")
    suspend fun clearThread(threadId: Long)

    @Query("DELETE FROM messages WHERE thread_id IN (SELECT id FROM threads WHERE project_id = :projectId)")
    suspend fun clearProject(projectId: Long)
}

/**
 * A prompt that runs on its own in a project. [kind] is one of the `ScheduleKind` names;
 * [nextRunAt] is epoch millis (0 = nothing pending), advanced by the scheduler as runs fire.
 */
@Entity(tableName = "schedules", indices = [Index("project_id")])
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "project_id") val projectId: Long,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "prompt") val prompt: String,
    @ColumnInfo(name = "kind") val kind: String = "DAILY",
    /** Minutes after local midnight, for DAILY / WEEKLY. */
    @ColumnInfo(name = "minute_of_day") val minuteOfDay: Int = 9 * 60,
    /** Bit 0 = Monday … bit 6 = Sunday, for WEEKLY. */
    @ColumnInfo(name = "days_mask") val daysMask: Int = 0b1111111,
    @ColumnInfo(name = "interval_minutes") val intervalMinutes: Int = 60,
    /** 1–31, for MONTHLY; months shorter than this run on their last day. */
    @ColumnInfo(name = "day_of_month") val dayOfMonth: Int = 1,
    @ColumnInfo(name = "enabled") val enabled: Boolean = true,
    @ColumnInfo(name = "next_run_at") val nextRunAt: Long = 0,
    @ColumnInfo(name = "last_run_at") val lastRunAt: Long = 0,
    /** "", OK, FAILED, TIMEOUT or SKIPPED. */
    @ColumnInfo(name = "last_status") val lastStatus: String = "",
    @ColumnInfo(name = "last_thread_id") val lastThreadId: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    /** Lifetime usage of all runs, including ones already pruned from the run history. */
    @ColumnInfo(name = "total_input_tokens", defaultValue = "0") val totalInputTokens: Long = 0,
    @ColumnInfo(name = "total_output_tokens", defaultValue = "0") val totalOutputTokens: Long = 0,
    @ColumnInfo(name = "total_cost_usd", defaultValue = "0") val totalCostUsd: Double = 0.0,
    @ColumnInfo(name = "total_cost_partial", defaultValue = "0") val totalCostPartial: Boolean = false,
)

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules WHERE project_id = :projectId ORDER BY created_at ASC")
    fun observeForProject(projectId: Long): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules")
    suspend fun listAll(): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun getById(id: Long): ScheduleEntity?

    @Query("SELECT * FROM schedules WHERE enabled = 1 AND next_run_at > 0 AND next_run_at <= :now")
    suspend fun listDue(now: Long): List<ScheduleEntity>

    @Query("SELECT MIN(next_run_at) FROM schedules WHERE enabled = 1 AND next_run_at > 0")
    suspend fun earliestNextRun(): Long?

    @Insert
    suspend fun insert(s: ScheduleEntity): Long

    @Update
    suspend fun update(s: ScheduleEntity)

    @Delete
    suspend fun delete(s: ScheduleEntity)

    @Query("DELETE FROM schedules WHERE project_id = :projectId")
    suspend fun deleteForProject(projectId: Long)

    /** Adds one run's usage to the schedule's lifetime totals without touching its other fields. */
    @Query(
        "UPDATE schedules SET total_input_tokens = total_input_tokens + :inputTokens, " +
            "total_output_tokens = total_output_tokens + :outputTokens, total_cost_usd = total_cost_usd + :costUsd, " +
            "total_cost_partial = MAX(total_cost_partial, :partial) WHERE id = :id"
    )
    suspend fun addUsage(id: Long, inputTokens: Long, outputTokens: Long, costUsd: Double, partial: Boolean)
}

/** One finished (or skipped) firing of a schedule; only the newest [ScheduleRunDao.KEEP] are kept. */
@Entity(tableName = "schedule_runs", indices = [Index("schedule_id")])
data class ScheduleRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "schedule_id") val scheduleId: Long,
    /** The chat the run happened in, or 0 when none was created (skipped). */
    @ColumnInfo(name = "thread_id") val threadId: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    /** OK, FAILED, TIMEOUT or SKIPPED. */
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "input_tokens") val inputTokens: Long = 0,
    @ColumnInfo(name = "output_tokens") val outputTokens: Long = 0,
    @ColumnInfo(name = "cost_usd") val costUsd: Double = 0.0,
    @ColumnInfo(name = "cost_partial") val costPartial: Boolean = false,
)

/** One model response's usage. Unlike the per-chat totals these survive deleting the chat. */
@Entity(tableName = "usage_events", indices = [Index("created_at")])
data class UsageEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "project_id") val projectId: Long,
    @ColumnInfo(name = "thread_id") val threadId: Long,
    @ColumnInfo(name = "model") val model: String,
    /** Blank for rows from before v15. */
    @ColumnInfo(name = "provider", defaultValue = "") val provider: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "input_tokens") val inputTokens: Long,
    @ColumnInfo(name = "output_tokens") val outputTokens: Long,
    @ColumnInfo(name = "cache_read_tokens", defaultValue = "0") val cacheReadTokens: Long = 0,
    @ColumnInfo(name = "cost_usd") val costUsd: Double,
    @ColumnInfo(name = "cost_partial") val costPartial: Boolean,
)

@Dao
interface UsageEventDao {
    @Insert
    suspend fun insert(e: UsageEventEntity)

    @Query("SELECT * FROM usage_events")
    fun observeAll(): Flow<List<UsageEventEntity>>
}

@Dao
interface ScheduleRunDao {
    @Query("SELECT * FROM schedule_runs")
    fun observeAll(): Flow<List<ScheduleRunEntity>>

    @Query("SELECT * FROM schedule_runs WHERE schedule_id = :scheduleId ORDER BY started_at DESC")
    fun observeForSchedule(scheduleId: Long): Flow<List<ScheduleRunEntity>>

    @Insert
    suspend fun insert(r: ScheduleRunEntity): Long

    @Query(
        "DELETE FROM schedule_runs WHERE schedule_id = :scheduleId AND id NOT IN " +
            "(SELECT id FROM schedule_runs WHERE schedule_id = :scheduleId ORDER BY started_at DESC LIMIT :keep)"
    )
    suspend fun prune(scheduleId: Long, keep: Int = KEEP)

    @Query("DELETE FROM schedule_runs WHERE schedule_id = :scheduleId")
    suspend fun deleteForSchedule(scheduleId: Long)

    @Query("DELETE FROM schedule_runs WHERE schedule_id IN (SELECT id FROM schedules WHERE project_id = :projectId)")
    suspend fun deleteForProject(projectId: Long)

    companion object {
        const val KEEP = 30
    }
}

@Database(
    entities = [
        ProjectEntity::class, ThreadEntity::class, MessageEntity::class, ScheduleEntity::class,
        ScheduleRunEntity::class, UsageEventEntity::class,
    ],
    version = 15,
    exportSchema = false,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun projects(): ProjectDao
    abstract fun threads(): ThreadDao
    abstract fun messages(): MessageDao
    abstract fun schedules(): ScheduleDao
    abstract fun scheduleRuns(): ScheduleRunDao
    abstract fun usageEvents(): UsageEventDao

    companion object {
        @Volatile private var instance: ChatDatabase? = null

        /** v3 → v4: add description + yolo_mode columns on projects. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN description TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE projects ADD COLUMN yolo_mode INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v4 → v5: per-project model and max-turns overrides. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN model_override TEXT")
                db.execSQL("ALTER TABLE projects ADD COLUMN max_turns_override INTEGER")
            }
        }

        /** v5 → v6: scheduled prompts. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS schedules (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "project_id INTEGER NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "prompt TEXT NOT NULL, " +
                        "kind TEXT NOT NULL, " +
                        "minute_of_day INTEGER NOT NULL, " +
                        "days_mask INTEGER NOT NULL, " +
                        "interval_minutes INTEGER NOT NULL, " +
                        "enabled INTEGER NOT NULL, " +
                        "next_run_at INTEGER NOT NULL, " +
                        "last_run_at INTEGER NOT NULL, " +
                        "last_status TEXT NOT NULL, " +
                        "last_thread_id INTEGER NOT NULL, " +
                        "created_at INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_schedules_project_id ON schedules (project_id)")
            }
        }

        /** v6 → v7: per-project opt-in for Lua network access (off for existing projects). */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN lua_enabled INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v7 → v8: day of month for monthly schedules. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE schedules ADD COLUMN day_of_month INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * v8 → v9: a project picks a saved profile instead of a free-text model. The table is rebuilt
         * because minSdk 31 ships SQLite without DROP COLUMN; the old model override has no profile to
         * map to, so it is dropped.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE projects_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "workspace_uri TEXT NOT NULL, " +
                        "description TEXT NOT NULL DEFAULT '', " +
                        "yolo_mode INTEGER NOT NULL DEFAULT 0, " +
                        "profile_override INTEGER, " +
                        "max_turns_override INTEGER, " +
                        "lua_enabled INTEGER NOT NULL DEFAULT 0, " +
                        "created_at INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO projects_new (id, name, workspace_uri, description, yolo_mode, " +
                        "max_turns_override, lua_enabled, created_at) " +
                        "SELECT id, name, workspace_uri, description, yolo_mode, " +
                        "max_turns_override, lua_enabled, created_at FROM projects"
                )
                db.execSQL("DROP TABLE projects")
                db.execSQL("ALTER TABLE projects_new RENAME TO projects")
            }
        }

        /** v9 → v10: token / cost totals on threads, and the per-schedule run history. */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE threads ADD COLUMN input_tokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE threads ADD COLUMN output_tokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE threads ADD COLUMN cost_usd REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE threads ADD COLUMN cost_partial INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS schedule_runs (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "schedule_id INTEGER NOT NULL, " +
                        "thread_id INTEGER NOT NULL, " +
                        "started_at INTEGER NOT NULL, " +
                        "duration_ms INTEGER NOT NULL, " +
                        "status TEXT NOT NULL, " +
                        "input_tokens INTEGER NOT NULL, " +
                        "output_tokens INTEGER NOT NULL, " +
                        "cost_usd REAL NOT NULL, " +
                        "cost_partial INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_schedule_runs_schedule_id ON schedule_runs (schedule_id)")
            }
        }

        /** v10 → v11: lifetime usage totals on schedules, seeded from the history kept so far. */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE schedules ADD COLUMN total_input_tokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE schedules ADD COLUMN total_output_tokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE schedules ADD COLUMN total_cost_usd REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE schedules ADD COLUMN total_cost_partial INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE schedules SET " +
                        "total_input_tokens = (SELECT COALESCE(SUM(input_tokens), 0) FROM schedule_runs WHERE schedule_id = schedules.id), " +
                        "total_output_tokens = (SELECT COALESCE(SUM(output_tokens), 0) FROM schedule_runs WHERE schedule_id = schedules.id), " +
                        "total_cost_usd = (SELECT COALESCE(SUM(cost_usd), 0) FROM schedule_runs WHERE schedule_id = schedules.id), " +
                        "total_cost_partial = (SELECT COALESCE(MAX(cost_partial), 0) FROM schedule_runs WHERE schedule_id = schedules.id)"
                )
            }
        }

        /** v11 → v12: flag threads started by schedules, replacing the "⏰ " title prefix. */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE threads ADD COLUMN from_schedule INTEGER NOT NULL DEFAULT 0")
                // "⏰ " is two characters; drop it from the title along with setting the flag.
                db.execSQL("UPDATE threads SET from_schedule = 1, title = SUBSTR(title, 3) WHERE title LIKE '⏰ %'")
            }
        }

        /** v12 → v13: per-response usage log (model, tokens, cost) for the stats page. */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS usage_events (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "project_id INTEGER NOT NULL, thread_id INTEGER NOT NULL, model TEXT NOT NULL, " +
                        "created_at INTEGER NOT NULL, input_tokens INTEGER NOT NULL, " +
                        "output_tokens INTEGER NOT NULL, cache_read_tokens INTEGER NOT NULL DEFAULT 0, " +
                        "cost_usd REAL NOT NULL, cost_partial INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_usage_events_created_at ON usage_events (created_at)")
            }
        }

        /** v13 → v14: record which model wrote each assistant message, for the stats breakdown. */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN model TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v14 → v15: record which provider served each response, for the stats breakdown. */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN provider TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE usage_events ADD COLUMN provider TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(context: Context): ChatDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ChatDatabase::class.java,
                "edo-chat.db",
            )
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
