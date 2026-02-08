package dev.dimension.flare.data.database.app.model

import androidx.room.Entity
import androidx.room.Index
import dev.dimension.flare.model.MicroBlogKey

@Entity(
    primaryKeys = ["accountKey", "userKey"],
    indices = [
        Index(value = ["accountKey"]),
        Index(value = ["userKey"]),
    ],
)
internal data class DbUserPreference(
    val accountKey: MicroBlogKey,
    val userKey: MicroBlogKey,
    val hideReposts: Boolean = false,
    val hideReplies: Boolean = false,
)
