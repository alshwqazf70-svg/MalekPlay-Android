package com.malekplay.data.repository

import com.malekplay.data.local.dao.UserDataDao
import com.malekplay.data.local.entity.FavouriteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserDataRepository @Inject constructor(
    private val userDataDao: UserDataDao
) {

    fun observeFavouriteIds(): Flow<Set<String>> =
        userDataDao.observeFavouriteIds().map { it.toSet() }

    suspend fun toggleFavourite(mediaId: String): Boolean = withContext(Dispatchers.IO) {
        userDataDao.toggleFavourite(mediaId, System.currentTimeMillis())
    }

    suspend fun setFavourite(mediaId: String, favourite: Boolean) = withContext(Dispatchers.IO) {
        if (favourite) {
            userDataDao.addFavourite(FavouriteEntity(mediaId = mediaId))
        } else {
            userDataDao.removeFavourite(mediaId)
        }
    }

    suspend fun isFavourite(mediaId: String): Boolean =
        withContext(Dispatchers.IO) { userDataDao.favouriteCount(mediaId) > 0 }

    /** One-shot read used by the backup exporter. */
    suspend fun observeFavouriteIdsSnapshot(): Set<String> = withContext(Dispatchers.IO) {
        runCatching { userDataDao.observeFavouriteIds().first() }
            .getOrDefault(emptyList())
            .toSet()
    }

    /** Replaces the whole favourite set during a restore. */
    suspend fun replaceFavourites(ids: Set<String>) = withContext(Dispatchers.IO) {
        userDataDao.clearFavourites()
        if (ids.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        ids.forEach { userDataDao.addFavourite(FavouriteEntity(it, now)) }
    }

    suspend fun recentHistoryIds(limit: Int): List<String> =
        withContext(Dispatchers.IO) { userDataDao.recentHistoryIds(limit) }

    suspend fun removeHistory(mediaId: String) =
        withContext(Dispatchers.IO) { userDataDao.removeHistory(mediaId) }

    suspend fun clearHistory() = withContext(Dispatchers.IO) { userDataDao.clearHistory() }
}
