package dev.dimension.flare.data.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.dimension.flare.data.database.app.AppDatabase
import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.database.scroll.ScrollPositionDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

internal fun provideAppDatabase(driverFactory: DriverFactory): AppDatabase =
    driverFactory
        .createBuilder<AppDatabase>("app.db")
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

internal const val CACHE_DATABASE_NAME = "cache.db"

internal fun provideCacheDatabase(driverFactory: DriverFactory): CacheDatabase =
    driverFactory
        .createBuilder<CacheDatabase>(CACHE_DATABASE_NAME, isCache = true)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

internal const val SCROLL_POSITION_DATABASE_NAME = "scroll_position.db"

internal fun provideScrollPositionDatabase(driverFactory: DriverFactory): ScrollPositionDatabase =
    driverFactory
        .createBuilder<ScrollPositionDatabase>(SCROLL_POSITION_DATABASE_NAME, isCache = false)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

