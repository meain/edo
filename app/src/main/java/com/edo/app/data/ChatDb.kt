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
    /** Null means "use the global setting". */
    @ColumnInfo(name = "model_override") val modelOverride: String? = null,
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
)

@Dao
interface ThreadDao {
    @Query("SELECT * FROM threads WHERE project_id = :projectId ORDER BY last_updated_at DESC")
    fun observeForProject(projectId: Long): Flow<List<ThreadEntity>>

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
}

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "thread_id", index = true) val threadId: Long,
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "content_json") val contentJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE thread_id = :threadId ORDER BY id ASC")
    suspend fun listForThread(threadId: Long): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE thread_id = :threadId")
    suspend fun countForThread(threadId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(msg: MessageEntity): Long

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
    @ColumnInfo(name = "enabled") val enabled: Boolean = true,
    @ColumnInfo(name = "next_run_at") val nextRunAt: Long = 0,
    @ColumnInfo(name = "last_run_at") val lastRunAt: Long = 0,
    /** "", OK, FAILED, TIMEOUT or SKIPPED. */
    @ColumnInfo(name = "last_status") val lastStatus: String = "",
    @ColumnInfo(name = "last_thread_id") val lastThreadId: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
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
}

@Database(
    entities = [ProjectEntity::class, ThreadEntity::class, MessageEntity::class, ScheduleEntity::class],
    version = 7,
    exportSchema = false,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun projects(): ProjectDao
    abstract fun threads(): ThreadDao
    abstract fun messages(): MessageDao
    abstract fun schedules(): ScheduleDao

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

        fun get(context: Context): ChatDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ChatDatabase::class.java,
                "edo-chat.db",
            )
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
