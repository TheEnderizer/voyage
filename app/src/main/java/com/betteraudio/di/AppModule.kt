package com.betteraudio.di

import android.content.Context
import androidx.room.Room
import com.betteraudio.data.db.AppDatabase
import com.betteraudio.data.db.dao.AudioFileDao
import com.betteraudio.data.db.dao.AudioPresetDao
import com.betteraudio.data.db.dao.AuthorMetaDao
import com.betteraudio.data.db.dao.BookDao
import com.betteraudio.data.db.dao.BookmarkDao
import com.betteraudio.data.db.dao.ChapterDao
import com.betteraudio.data.db.dao.CompanionPackDao
import com.betteraudio.data.db.dao.WidgetDesignDao
import com.betteraudio.data.db.dao.ListeningHistoryDao
import com.betteraudio.data.db.dao.PlaybackProgressDao
import com.betteraudio.data.db.dao.SeriesDao
import com.betteraudio.data.db.dao.WidgetBindingDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** A process-lifetime coroutine scope for work that must outlive any single screen/ViewModel —
 *  e.g. a backup restore started from Settings shouldn't be cancelled by navigating away. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "betteraudio.db")
            .addMigrations(AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14, AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16, AppDatabase.MIGRATION_16_17, AppDatabase.MIGRATION_17_18, AppDatabase.MIGRATION_18_19, AppDatabase.MIGRATION_19_20, AppDatabase.MIGRATION_20_21, AppDatabase.MIGRATION_21_22, AppDatabase.MIGRATION_22_23, AppDatabase.MIGRATION_23_25, AppDatabase.MIGRATION_24_25, AppDatabase.MIGRATION_25_26, AppDatabase.MIGRATION_26_27, AppDatabase.MIGRATION_27_28)
            // No fallbackToDestructiveMigration(): it catches the wrong failure. A WRONG migration
            // crashes on launch (Room validates the resulting schema) — the fallback never fires
            // for that. What it actually did was silently wipe every user's library, progress,
            // bookmarks and listening history if a migration was ever forgotten. A missing
            // migration should crash loudly, not erase the library quietly.
            .build()

    @Provides fun provideBookDao(db: AppDatabase): BookDao = db.bookDao()
    @Provides fun provideAudioFileDao(db: AppDatabase): AudioFileDao = db.audioFileDao()
    @Provides fun providePlaybackProgressDao(db: AppDatabase): PlaybackProgressDao = db.playbackProgressDao()
    @Provides fun provideChapterDao(db: AppDatabase): ChapterDao = db.chapterDao()
    @Provides fun provideCompanionPackDao(db: AppDatabase): CompanionPackDao = db.companionPackDao()
    @Provides fun provideBookmarkDao(db: AppDatabase): BookmarkDao = db.bookmarkDao()
    @Provides fun provideAudioPresetDao(db: AppDatabase): AudioPresetDao = db.audioPresetDao()
    @Provides fun provideListeningHistoryDao(db: AppDatabase): ListeningHistoryDao = db.listeningHistoryDao()
    @Provides fun provideSeriesDao(db: AppDatabase): SeriesDao = db.seriesDao()
    @Provides fun provideAuthorMetaDao(db: AppDatabase): AuthorMetaDao = db.authorMetaDao()
    @Provides fun provideWidgetDesignDao(db: AppDatabase): WidgetDesignDao = db.widgetDesignDao()
    @Provides fun provideWidgetBindingDao(db: AppDatabase): WidgetBindingDao = db.widgetBindingDao()
}
