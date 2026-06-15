package com.edo.app.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
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

@Database(
    entities = [ProjectEntity::class, ThreadEntity::class, MessageEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun projects(): ProjectDao
    abstract fun threads(): ThreadDao
    abstract fun messages(): MessageDao

    companion object {
        @Volatile private var instance: ChatDatabase? = null

        /** v3 → v4: add description + yolo_mode columns on projects. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN description TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE projects ADD COLUMN yolo_mode INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): ChatDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ChatDatabase::class.java,
                "edo-chat.db",
            )
                .addMigrations(MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
