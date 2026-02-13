package dev.dimension.flare.data.database.app.model

import androidx.room.Entity
import androidx.room.Index
import dev.dimension.flare.model.MicroBlogKey

@Entity(
    primaryKeys = ["userKey", "accountKey"],
    indices = [
        Index(value = ["accountKey"]),
        Index(value = ["userKey", "accountKey"], unique = true),
    ],
)
internal data class DbUserPreference(
    val userKey: MicroBlogKey,
    val accountKey: MicroBlogKey,
    val hideReposts: Boolean = false,
    val hideReplies: Boolean = false,
)
