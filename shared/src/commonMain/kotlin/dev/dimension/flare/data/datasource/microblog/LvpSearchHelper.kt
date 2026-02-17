package dev.dimension.flare.data.datasource.microblog

import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.model.MicroBlogKey

/**
 * LVP (Last Viewed Post) search helper for maintaining scroll position during refresh.
 *
 * When a feed is manually refreshed, this helps locate the LVP in the new data.
 * If not found, it can attempt to load older pages to find it.
 */
internal class LvpSearchHelper(
    private val database: CacheDatabase,
) {
    /**
     * Check if a specific status exists in the database for this feed.
     */
    suspend fun statusExistsInFeed(
        pagingKey: String,
        statusKey: MicroBlogKey,
    ): Boolean {
        return database.pagingTimelineDao()
            .statusExistsInFeed(pagingKey, statusKey)
    }

    /**
     * Get the most recent LVP for a feed, or null if none exists.
     */
    suspend fun getLastViewedPosition(pagingKey: String): LvpData? {
        val scrollPos = database.pagingTimelineDao().getScrollPosition(pagingKey)
        return if (scrollPos?.lastViewedStatusKey != null && scrollPos.lastViewedSortId != null) {
            LvpData(
                statusKey = scrollPos.lastViewedStatusKey,
                sortId = scrollPos.lastViewedSortId,
                timestamp = scrollPos.lastUpdated,
            )
        } else {
            null
        }
    }

    data class LvpData(
        val statusKey: MicroBlogKey,
        val sortId: Long,
        val timestamp: Long,
    )
}


