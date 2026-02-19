package dev.dimension.flare.data.database.scroll.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.dimension.flare.model.MicroBlogKey

@Entity(tableName = "feed_scroll_position")
public data class DbFeedScrollPosition(
    @PrimaryKey
    val pagingKey: String,
    val lastViewedStatusKey: MicroBlogKey?,
    val lastViewedSortId: Long?,
    val lastUpdated: Long,
)

