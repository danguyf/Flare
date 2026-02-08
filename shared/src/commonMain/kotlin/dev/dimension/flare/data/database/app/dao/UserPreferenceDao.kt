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

    @Query("SELECT * FROM DbUserPreference WHERE accountKey = :accountKey AND userKey = :userKey")
    fun get(
        accountKey: MicroBlogKey,
        userKey: MicroBlogKey,
    ): Flow<DbUserPreference?>

    @Query("SELECT * FROM DbUserPreference WHERE accountKey = :accountKey AND userKey = :userKey")
    suspend fun getSync(
        accountKey: MicroBlogKey,
        userKey: MicroBlogKey,
    ): DbUserPreference?

    @Query("SELECT * FROM DbUserPreference WHERE accountKey = :accountKey")
    fun getAllForAccount(accountKey: MicroBlogKey): Flow<List<DbUserPreference>>

    @Query("DELETE FROM DbUserPreference WHERE accountKey = :accountKey AND userKey = :userKey")
    suspend fun delete(
        accountKey: MicroBlogKey,
        userKey: MicroBlogKey,
    )

    @Query("DELETE FROM DbUserPreference WHERE accountKey = :accountKey")
    suspend fun deleteAllForAccount(accountKey: MicroBlogKey)
}
