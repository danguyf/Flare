package dev.dimension.flare.data.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.dimension.flare.data.database.app.AppDatabase
import dev.dimension.flare.data.database.cache.CacheDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

internal fun provideAppDatabase(driverFactory: DriverFactory): AppDatabase =
    driverFactory
        .createBuilder<AppDatabase>("app.db")
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

internal const val CACHE_DATABASE_NAME = "cache.db"

private val MIGRATION_22_23 =
    object : Migration(22, 23) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS DbFeedScrollPosition (
                    pagingKey TEXT NOT NULL PRIMARY KEY,
                    lastViewedStatusKey TEXT,
                    lastViewedSortId INTEGER,
                    lastUpdated INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS index_DbFeedScrollPosition_pagingKey 
                ON DbFeedScrollPosition(pagingKey)
                """.trimIndent(),
            )
        }
    }

internal fun provideCacheDatabase(driverFactory: DriverFactory): CacheDatabase =
    driverFactory
        .createBuilder<CacheDatabase>(CACHE_DATABASE_NAME, isCache = true)
        .addMigrations(MIGRATION_22_23)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
