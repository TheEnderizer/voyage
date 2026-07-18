package com.betteraudio.widget

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** AppWidgetProvider instances aren't Hilt-injected (the system constructs them via reflection),
 *  so they reach the app singleton through this entry point instead. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun widgetUpdater(): WidgetUpdater
}
