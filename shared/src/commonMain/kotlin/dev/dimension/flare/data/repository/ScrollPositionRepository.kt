package dev.dimension.flare.data.repository

import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.database.cache.model.DbFeedScrollPosition
import dev.dimension.flare.model.MicroBlogKey

/**
 * Public API for managing scroll positions.
 */
public class ScrollPositionRepository
    internal constructor(
        private val database: CacheDatabase,
    ) {
    public suspend fun getScrollPosition(pagingKey: String): DbFeedScrollPosition? =
        database.pagingTimelineDao().getScrollPosition(pagingKey)

    public suspend fun saveScrollPosition(scrollPosition: DbFeedScrollPosition): Unit =
        database.pagingTimelineDao().saveScrollPosition(scrollPosition)

    public suspend fun getSortIdForStatus(
        pagingKey: String,
        statusKey: MicroBlogKey,
    ): Long? =
        database.pagingTimelineDao().getSortIdForStatus(pagingKey, statusKey)

    public suspend fun countNewerItems(
        pagingKey: String,
        sortId: Long,
    ): Int =
        database.pagingTimelineDao().countNewerItems(pagingKey, sortId)

    public suspend fun statusExistsInFeed(
        pagingKey: String,
        statusKey: MicroBlogKey,
    ): Boolean =
        database.pagingTimelineDao().statusExistsInFeed(pagingKey, statusKey)
}


