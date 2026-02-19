package dev.dimension.flare.data.repository

import dev.dimension.flare.data.database.scroll.ScrollPositionDatabase
import dev.dimension.flare.data.database.scroll.model.DbFeedScrollPosition

/**
 * Public API for managing scroll positions.
 * Scroll positions are stored in a separate database that survives cache invalidation.
 */
public class ScrollPositionRepository
    internal constructor(
        private val database: ScrollPositionDatabase,
    ) {
        public suspend fun getScrollPosition(pagingKey: String): DbFeedScrollPosition? =
            database.scrollPositionDao().getScrollPosition(pagingKey)

        public suspend fun saveScrollPosition(scrollPosition: DbFeedScrollPosition): Unit =
            database.scrollPositionDao().saveScrollPosition(scrollPosition)
    }
