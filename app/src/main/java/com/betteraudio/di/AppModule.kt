package com.betteraudio.di

import android.content.Context
import androidx.room.Room
import com.betteraudio.data.db.AppDatabase
import com.betteraudio.data.db.dao.AudioFileDao
import com.betteraudio.data.db.dao.AudioPresetDao
import com.betteraudio.data.db.dao.AuthorMetaDao
import com.betteraudio.data.db.dao.BookDao
import com.betteraudio.data.db.dao.BookGroupDao
import com.betteraudio.data.db.dao.BookmarkDao
import com.betteraudio.data.db.dao.ChapterDao
import com.betteraudio.data.db.dao.CustomWidgetDesignDao
import com.betteraudio.data.db.dao.ListeningHistoryDao
import com.betteraudio.data.db.dao.PlaybackProgressDao
import com.betteraudio.data.db.dao.SeriesDao
import com.betteraudio.data.db.dao.WidgetBindingDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "betteraudio.db")
            .addMigrations(AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14, AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16)
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideBookDao(db: AppDatabase): BookDao = db.bookDao()
    @Provides fun provideAudioFileDao(db: AppDatabase): AudioFileDao = db.audioFileDao()
    @Provides fun providePlaybackProgressDao(db: AppDatabase): PlaybackProgressDao = db.playbackProgressDao()
    @Provides fun provideBookGroupDao(db: AppDatabase): BookGroupDao = db.bookGroupDao()
    @Provides fun provideChapterDao(db: AppDatabase): ChapterDao = db.chapterDao()
    @Provides fun provideBookmarkDao(db: AppDatabase): BookmarkDao = db.bookmarkDao()
    @Provides fun provideAudioPresetDao(db: AppDatabase): AudioPresetDao = db.audioPresetDao()
    @Provides fun provideListeningHistoryDao(db: AppDatabase): ListeningHistoryDao = db.listeningHistoryDao()
    @Provides fun provideSeriesDao(db: AppDatabase): SeriesDao = db.seriesDao()
    @Provides fun provideAuthorMetaDao(db: AppDatabase): AuthorMetaDao = db.authorMetaDao()
    @Provides fun provideSyncAnchorDao(db: AppDatabase): com.betteraudio.data.db.dao.SyncAnchorDao = db.syncAnchorDao()
    @Provides fun provideCustomWidgetDesignDao(db: AppDatabase): CustomWidgetDesignDao = db.customWidgetDesignDao()
    @Provides fun provideWidgetBindingDao(db: AppDatabase): WidgetBindingDao = db.widgetBindingDao()
}
