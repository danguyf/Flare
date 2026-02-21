package dev.dimension.flare.data.database.scroll.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.dimension.flare.data.database.scroll.model.DbFeedScrollPosition

@Dao
internal interface ScrollPositionDao {
    @Query("SELECT * FROM feed_scroll_position WHERE pagingKey = :pagingKey")
    suspend fun getScrollPosition(pagingKey: String): DbFeedScrollPosition?

    @Upsert
    suspend fun saveScrollPosition(scrollPosition: DbFeedScrollPosition)

    @Query("DELETE FROM feed_scroll_position WHERE pagingKey = :pagingKey")
    suspend fun deleteScrollPosition(pagingKey: String)
}
