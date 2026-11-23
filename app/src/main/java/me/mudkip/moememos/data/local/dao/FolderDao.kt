package me.mudkip.moememos.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.mudkip.moememos.data.local.entity.FolderEntity

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders WHERE accountKey = :accountKey ORDER BY title ASC")
    fun observeFolders(accountKey: String): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE accountKey = :accountKey ORDER BY title ASC")
    suspend fun getFolders(accountKey: String): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE name = :name AND accountKey = :accountKey LIMIT 1")
    suspend fun getFolderByName(name: String, accountKey: String): FolderEntity?

    @Upsert
    suspend fun insertFolders(folders: List<FolderEntity>)

    @Upsert
    suspend fun insertFolder(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE name = :name AND accountKey = :accountKey")
    suspend fun deleteFolder(name: String, accountKey: String)

    @Query("DELETE FROM folders WHERE (name = :name OR parent = :name) AND accountKey = :accountKey")
    suspend fun deleteFolderAndChildren(name: String, accountKey: String)

    @Query("DELETE FROM folders WHERE accountKey = :accountKey")
    suspend fun clearFolders(accountKey: String)
}
