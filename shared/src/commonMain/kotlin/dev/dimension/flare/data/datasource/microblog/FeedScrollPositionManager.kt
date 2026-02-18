package dev.dimension.flare.data.datasource.microblog

import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.database.cache.model.DbFeedScrollPosition
import dev.dimension.flare.model.MicroBlogKey
import kotlin.time.Clock

/**
 * Manages scroll position (Last Viewed Post) for feeds.
 */
internal class FeedScrollPositionManager(
    private val database: CacheDatabase,
) {
    /**
     * Save the current scroll position for a feed.
     */
    suspend fun saveScrollPosition(
        pagingKey: String,
        lastViewedStatusKey: MicroBlogKey?,
        lastViewedSortId: Long?,
    ) {
        database.pagingTimelineDao().saveScrollPosition(
            DbFeedScrollPosition(
                pagingKey = pagingKey,
                lastViewedStatusKey = lastViewedStatusKey,
                lastViewedSortId = lastViewedSortId,
                lastUpdated = Clock.System.now().toEpochMilliseconds(),
            ),
        )
    }

    /**
     * Load the scroll position for a feed.
     */
    suspend fun getScrollPosition(pagingKey: String): DbFeedScrollPosition? = database.pagingTimelineDao().getScrollPosition(pagingKey)

    /**
     * Clear scroll position for a specific feed.
     */
    suspend fun clearScrollPosition(pagingKey: String) {
        database.pagingTimelineDao().deleteScrollPosition(pagingKey)
    }

    /**
     * Clear all scroll positions.
     */
    suspend fun clearAllScrollPositions() {
        database.pagingTimelineDao().clearScrollPositions()
    }
}
