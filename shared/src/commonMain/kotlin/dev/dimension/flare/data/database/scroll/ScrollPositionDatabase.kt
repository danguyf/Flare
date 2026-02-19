package dev.dimension.flare.data.database.scroll

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import dev.dimension.flare.data.database.adapter.MicroBlogKeyConverter
import dev.dimension.flare.data.database.scroll.dao.ScrollPositionDao
import dev.dimension.flare.data.database.scroll.model.DbFeedScrollPosition

internal const val SCROLL_POSITION_DATABASE_VERSION = 1

@Database(
    entities = [
        DbFeedScrollPosition::class,
    ],
    version = SCROLL_POSITION_DATABASE_VERSION,
    exportSchema = true,
)
@TypeConverters(
    MicroBlogKeyConverter::class,
)
@ConstructedBy(ScrollPositionDatabaseConstructor::class)
internal abstract class ScrollPositionDatabase : RoomDatabase() {
    abstract fun scrollPositionDao(): ScrollPositionDao
}

// The Room compiler generates the `actual` implementations.
@Suppress("NO_ACTUAL_FOR_EXPECT")
internal expect object ScrollPositionDatabaseConstructor : RoomDatabaseConstructor<ScrollPositionDatabase> {
    override fun initialize(): ScrollPositionDatabase
}

internal suspend fun <R> ScrollPositionDatabase.connect(block: suspend () -> R): R =
    useWriterConnection {
        it.immediateTransaction {
            block.invoke()
        }
    }

