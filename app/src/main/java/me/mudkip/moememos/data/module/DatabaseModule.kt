package me.mudkip.moememos.data.module

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import me.mudkip.moememos.data.local.FileStorage
import me.mudkip.moememos.data.local.MoeMemosDatabase
import me.mudkip.moememos.data.local.dao.FolderDao
import me.mudkip.moememos.data.local.dao.MemoDao
import me.mudkip.moememos.data.repository.LocalDatabaseRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    
    @Singleton
    @Provides
    fun provideDatabase(@ApplicationContext context: Context): MoeMemosDatabase {
        return MoeMemosDatabase.getDatabase(context)
    }
    
    @Singleton
    @Provides
    fun provideMemoDao(database: MoeMemosDatabase) = database.memoDao()

    @Singleton
    @Provides
    fun provideFolderDao(database: MoeMemosDatabase) = database.folderDao()

    @Singleton
    @Provides
    fun provideLocalDatabaseRepository(
        memoDao: MemoDao,
        fileStorage: FileStorage,
        folderDao: FolderDao
    ) = LocalDatabaseRepository(memoDao, fileStorage, folderDao = folderDao)
} 
