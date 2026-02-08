package dev.dimension.flare.data.repository

import dev.dimension.flare.data.database.app.AppDatabase
import dev.dimension.flare.data.database.app.model.DbUserPreference
import dev.dimension.flare.model.MicroBlogKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class UserPreferenceRepository(
    private val database: AppDatabase,
) {
    fun getPreference(
        accountKey: MicroBlogKey,
        userKey: MicroBlogKey,
    ): Flow<DbUserPreference> =
        database
            .userPreferenceDao()
            .get(accountKey, userKey)
            .map { it ?: DbUserPreference(accountKey, userKey) }

    suspend fun getPreferenceSync(
        accountKey: MicroBlogKey,
        userKey: MicroBlogKey,
    ): DbUserPreference =
        database
            .userPreferenceDao()
            .getSync(accountKey, userKey)
            ?: DbUserPreference(accountKey, userKey)

    suspend fun updateHideReposts(
        accountKey: MicroBlogKey,
        userKey: MicroBlogKey,
        hide: Boolean,
    ) {
        val current = getPreferenceSync(accountKey, userKey)
        database.userPreferenceDao().insert(current.copy(hideReposts = hide))
    }

    suspend fun updateHideReplies(
        accountKey: MicroBlogKey,
        userKey: MicroBlogKey,
        hide: Boolean,
    ) {
        val current = getPreferenceSync(accountKey, userKey)
        database.userPreferenceDao().insert(current.copy(hideReplies = hide))
    }

    fun getAllForAccount(accountKey: MicroBlogKey): Flow<List<DbUserPreference>> =
        database.userPreferenceDao().getAllForAccount(accountKey)
}
