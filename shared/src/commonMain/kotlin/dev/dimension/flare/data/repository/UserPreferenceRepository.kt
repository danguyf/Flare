package dev.dimension.flare.data.repository

import dev.dimension.flare.data.database.app.AppDatabase
import dev.dimension.flare.data.database.app.model.DbUserPreference
import dev.dimension.flare.model.MicroBlogKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Repository for managing per-user preferences with in-memory caching.
 * Preferences are scoped per account.
 */
internal class UserPreferenceRepository(
    private val database: AppDatabase,
    private val accountKey: MicroBlogKey,
    scope: CoroutineScope,
) {
    // In-memory cache: userKey -> (hideReposts, hideReplies)
    private val _cache = MutableStateFlow<Map<MicroBlogKey, Pair<Boolean, Boolean>>>(emptyMap())
    val cache: StateFlow<Map<MicroBlogKey, Pair<Boolean, Boolean>>> = _cache.asStateFlow()

    init {
        // Load all preferences for this account and maintain cache
        database.userPreferenceDao()
            .observeAll(accountKey)
            .onEach { preferences ->
                _cache.value = preferences.associate { pref ->
                    pref.userKey to (pref.hideReposts to pref.hideReplies)
                }
            }
            .launchIn(scope)
    }

    /**
     * Get whether reposts should be hidden for a specific user.
     */
    fun getHideReposts(userKey: MicroBlogKey): Boolean =
        _cache.value[userKey]?.first ?: false

    /**
     * Get whether replies should be hidden for a specific user.
     */
    fun getHideReplies(userKey: MicroBlogKey): Boolean =
        _cache.value[userKey]?.second ?: false

    /**
     * Set whether to hide reposts for a specific user.
     */
    suspend fun setHideReposts(
        userKey: MicroBlogKey,
        hide: Boolean,
    ) {
        val existing = database.userPreferenceDao().get(userKey, accountKey)
        val updated =
            if (existing != null) {
                existing.copy(hideReposts = hide)
            } else {
                DbUserPreference(
                    userKey = userKey,
                    accountKey = accountKey,
                    hideReposts = hide,
                    hideReplies = false,
                )
            }

        // If both are false, delete the entry to keep database clean
        if (!updated.hideReposts && !updated.hideReplies) {
            database.userPreferenceDao().delete(userKey, accountKey)
        } else {
            database.userPreferenceDao().insert(updated)
        }
    }

    /**
     * Set whether to hide replies for a specific user.
     */
    suspend fun setHideReplies(
        userKey: MicroBlogKey,
        hide: Boolean,
    ) {
        val existing = database.userPreferenceDao().get(userKey, accountKey)
        val updated =
            if (existing != null) {
                existing.copy(hideReplies = hide)
            } else {
                DbUserPreference(
                    userKey = userKey,
                    accountKey = accountKey,
                    hideReposts = false,
                    hideReplies = hide,
                )
            }

        // If both are false, delete the entry to keep database clean
        if (!updated.hideReposts && !updated.hideReplies) {
            database.userPreferenceDao().delete(userKey, accountKey)
        } else {
            database.userPreferenceDao().insert(updated)
        }
    }

    /**
     * Toggle hide reposts for a specific user.
     */
    suspend fun toggleHideReposts(userKey: MicroBlogKey) {
        val current = getHideReposts(userKey)
        setHideReposts(userKey, !current)
    }

    /**
     * Toggle hide replies for a specific user.
     */
    suspend fun toggleHideReplies(userKey: MicroBlogKey) {
        val current = getHideReplies(userKey)
        setHideReplies(userKey, !current)
    }

    /**
     * Clear all preferences for the current account.
     */
    suspend fun clearAccount() {
        database.userPreferenceDao().deleteByAccount(accountKey)
    }
}
