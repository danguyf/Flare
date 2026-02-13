package dev.dimension.flare.data.database.app.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.dimension.flare.data.database.app.model.DbUserPreference
import dev.dimension.flare.model.MicroBlogKey
import kotlinx.coroutines.flow.Flow

@Dao
internal interface UserPreferenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(preference: DbUserPreference)

    @Query("SELECT * FROM DbUserPreference WHERE userKey = :userKey AND accountKey = :accountKey")
    suspend fun get(
        userKey: MicroBlogKey,
        accountKey: MicroBlogKey,
    ): DbUserPreference?

    @Query("SELECT * FROM DbUserPreference WHERE userKey = :userKey AND accountKey = :accountKey")
    fun observe(
        userKey: MicroBlogKey,
        accountKey: MicroBlogKey,
    ): Flow<DbUserPreference?>

    @Query("SELECT * FROM DbUserPreference WHERE accountKey = :accountKey")
    fun observeAll(accountKey: MicroBlogKey): Flow<List<DbUserPreference>>

    @Query("DELETE FROM DbUserPreference WHERE userKey = :userKey AND accountKey = :accountKey")
    suspend fun delete(
        userKey: MicroBlogKey,
        accountKey: MicroBlogKey,
    )

    @Query("DELETE FROM DbUserPreference WHERE accountKey = :accountKey")
    suspend fun deleteByAccount(accountKey: MicroBlogKey)
}
