package com.malekplay.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.malekplay.data.local.entity.FavouriteEntity
import com.malekplay.data.local.entity.HistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDataDao {

    // ------------------------------------------------------------ favourites

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addFavourite(entity: FavouriteEntity)

    @Query("DELETE FROM favourites WHERE mediaId = :mediaId")
    suspend fun removeFavourite(mediaId: String)

    @Query("SELECT mediaId FROM favourites")
    fun observeFavouriteIds(): Flow<List<String>>

    /** COUNT(*) maps to Int; Boolean is not an automatic Room mapping. */
    @Query("SELECT COUNT(*) FROM favourites WHERE mediaId = :mediaId")
    suspend fun favouriteCount(mediaId: String): Int

    // --------------------------------------------------------------- history

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHistory(entity: HistoryEntity)

    @Query("SELECT * FROM history WHERE mediaId = :mediaId LIMIT 1")
    suspend fun getHistory(mediaId: String): HistoryEntity?

    @Query("UPDATE history SET lastPlayedAtMs = :playedAtMs, playCount = :playCount WHERE mediaId = :mediaId")
    suspend fun updateHistory(mediaId: String, playedAtMs: Long, playCount: Int)

    @Query("SELECT mediaId FROM history ORDER BY lastPlayedAtMs DESC LIMIT :limit")
    suspend fun recentHistoryIds(limit: Int): List<String>

    /** Keeps the table bounded so it cannot grow without limit. */
    @Query(
        """DELETE FROM history WHERE mediaId NOT IN
           (SELECT mediaId FROM history ORDER BY lastPlayedAtMs DESC LIMIT 200)"""
    )
    suspend fun trimHistory()

    @Query("DELETE FROM history WHERE mediaId = :mediaId")
    suspend fun removeHistory(mediaId: String)

    @Query("DELETE FROM favourites")
    suspend fun clearFavourites()

    @Query("DELETE FROM history")
    suspend fun clearHistory()
}
