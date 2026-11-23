package me.mudkip.moememos.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.mudkip.moememos.data.local.dao.FolderDao
import me.mudkip.moememos.data.local.dao.MemoDao
import me.mudkip.moememos.data.local.entity.FolderEntity
import me.mudkip.moememos.data.local.entity.MemoEntity
import me.mudkip.moememos.data.local.entity.ResourceEntity

@Database(
    entities = [MemoEntity::class, ResourceEntity::class, FolderEntity::class],
    version = 3
)
@TypeConverters(Converters::class)
abstract class MoeMemosDatabase : RoomDatabase() {
    abstract fun memoDao(): MemoDao
    abstract fun folderDao(): FolderDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memos ADD COLUMN location_placeholder TEXT")
                db.execSQL("ALTER TABLE memos ADD COLUMN location_latitude REAL")
                db.execSQL("ALTER TABLE memos ADD COLUMN location_longitude REAL")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memos ADD COLUMN folder TEXT")
                db.execSQL("ALTER TABLE memos ADD COLUMN title TEXT")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS folders (
                        name TEXT NOT NULL PRIMARY KEY,
                        accountKey TEXT NOT NULL,
                        title TEXT NOT NULL,
                        parent TEXT,
                        createTime INTEGER,
                        updateTime INTEGER
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_folders_accountKey ON folders(accountKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_folders_accountKey_name ON folders(accountKey, name)")
            }
        }

        @Volatile
        private var INSTANCE: MoeMemosDatabase? = null

        fun getDatabase(context: Context): MoeMemosDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MoeMemosDatabase::class.java,
                    "moememos_database_localfirst"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
