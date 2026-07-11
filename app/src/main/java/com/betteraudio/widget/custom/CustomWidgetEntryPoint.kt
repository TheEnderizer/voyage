package com.betteraudio.widget.custom

import com.betteraudio.data.db.dao.CustomWidgetDesignDao
import com.betteraudio.data.db.dao.WidgetBindingDao
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.settings.SettingsStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * AppWidgetProvider/ConfigureActivity instances aren't Hilt-injected (the system constructs them
 * via reflection), so they reach app singletons through this entry point instead.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CustomWidgetEntryPoint {
    fun customWidgetDesignDao(): CustomWidgetDesignDao
    fun widgetBindingDao(): WidgetBindingDao
    fun settingsStore(): SettingsStore
    fun audiobookRepository(): AudiobookRepository
}
