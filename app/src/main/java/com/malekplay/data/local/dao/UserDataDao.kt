package com.malekplay.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.malekplay.data.local.entity.FavouriteEntity
import com.malekplay.data.local.entity.HistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDataDao {

    // ------------------------------------------------------------ favourites

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addFavourite(entity: FavouriteEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addFavourites(entities: List<FavouriteEntity>)

    /** Replaces a restored favourite set as one database transaction. */
    @Transaction
    suspend fun replaceFavourites(ids: Set<String>, addedAtMs: Long) {
        clearFavourites()
        if (ids.isNotEmpty()) {
            addFavourites(ids.map { FavouriteEntity(mediaId = it, addedAtMs = addedAtMs) })
        }
    }

    @Query("DELETE FROM favourites WHERE mediaId = :mediaId")
    suspend fun removeFavourite(mediaId: String)

    @Query("SELECT mediaId FROM favourites")
    fun observeFavouriteIds(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM favourites WHERE mediaId = :mediaId")
    suspend fun favouriteCount(mediaId: String): Int

    /** Toggle is one transaction, preventing two simultaneous taps from racing. */
    @Transaction
    suspend fun toggleFavourite(mediaId: String, addedAtMs: Long): Boolean {
        return if (favouriteCount(mediaId) > 0) {
            removeFavourite(mediaId)
            false
        } else {
            addFavourite(FavouriteEntity(mediaId = mediaId, addedAtMs = addedAtMs))
            true
        }
    }

    // --------------------------------------------------------------- history

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHistory(entity: HistoryEntity)

    @Query("SELECT * FROM history WHERE mediaId = :mediaId LIMIT 1")
    suspend fun getHistory(mediaId: String): HistoryEntity?

    /** Atomic upsert: concurrent play events cannot overwrite each other's count. */
    @Query(
        """INSERT INTO history(mediaId, lastPlayedAtMs, playCount)
           VALUES(:mediaId, :playedAtMs, 1)
           ON CONFLICT(mediaId) DO UPDATE SET
               lastPlayedAtMs = excluded.lastPlayedAtMs,
               playCount = history.playCount + 1"""
    )
    suspend fun recordHistoryPlay(mediaId: String, playedAtMs: Long)

    @Query("UPDATE history SET lastPlayedAtMs = :playedAtMs, playCount = :playCount WHERE mediaId = :mediaId")
    suspend fun updateHistory(mediaId: String, playedAtMs: Long, playCount: Int)

    @Query("SELECT mediaId FROM history ORDER BY lastPlayedAtMs DESC LIMIT :limit")
    suspend fun recentHistoryIds(limit: Int): List<String>

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
