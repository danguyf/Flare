package dev.dimension.flare.data.datasource.microblog

import dev.dimension.flare.data.database.scroll.ScrollPositionDatabase
import dev.dimension.flare.data.database.scroll.model.DbFeedScrollPosition
import dev.dimension.flare.model.MicroBlogKey
import kotlin.time.Clock

/**
 * Manages scroll position (Last Viewed Post) for feeds.
 */
internal class FeedScrollPositionManager(
    private val database: ScrollPositionDatabase,
) {
    /**
     * Save the current scroll position for a feed.
     */
    suspend fun saveScrollPosition(
        pagingKey: String,
        lastViewedStatusKey: MicroBlogKey?,
        lastViewedSortId: Long?,
    ) {
        database.scrollPositionDao().saveScrollPosition(
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
    suspend fun getScrollPosition(pagingKey: String): DbFeedScrollPosition? = database.scrollPositionDao().getScrollPosition(pagingKey)

    /**
     * Clear scroll position for a specific feed.
     */
    suspend fun clearScrollPosition(pagingKey: String) {
        database.scrollPositionDao().deleteScrollPosition(pagingKey)
    }
}
