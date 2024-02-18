package com.ammar.wallflow.data.repository

import android.content.Context
import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.filter
import androidx.paging.map
import com.ammar.wallflow.IoDispatcher
import com.ammar.wallflow.data.db.dao.LightDarkDao
import com.ammar.wallflow.data.db.dao.wallpaper.RedditWallpapersDao
import com.ammar.wallflow.data.db.dao.wallpaper.WallhavenWallpapersDao
import com.ammar.wallflow.data.db.entity.LightDarkEntity
import com.ammar.wallflow.data.db.entity.wallpaper.toWallpaper
import com.ammar.wallflow.data.repository.local.LocalWallpapersRepository
import com.ammar.wallflow.data.repository.utils.successOr
import com.ammar.wallflow.model.Source
import com.ammar.wallflow.model.Wallpaper
import com.ammar.wallflow.model.isUnspecified
import com.ammar.wallflow.model.wallhaven.wallhavenWallpaper1
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

@Singleton
class LightDarkRepository @Inject constructor(
    private val lightDarkDao: LightDarkDao,
    private val wallhavenWallpapersDao: WallhavenWallpapersDao,
    private val redditWallpapersDao: RedditWallpapersDao,
    private val localWallpapersRepository: LocalWallpapersRepository,
    @IoDispatcher val ioDispatcher: CoroutineDispatcher,
) {
    fun observeAll() = lightDarkDao.observeAll()

    @OptIn(ExperimentalPagingApi::class)
    fun wallpapersPager(
        context: Context,
        pageSize: Int = 24,
        prefetchDistance: Int = pageSize,
        initialLoadSize: Int = pageSize * 3,
    ): Flow<PagingData<Wallpaper>> = Pager(
        config = PagingConfig(
            pageSize = pageSize,
            prefetchDistance = prefetchDistance,
            initialLoadSize = initialLoadSize,
        ),
        remoteMediator = null,
        pagingSourceFactory = { lightDarkDao.pagingSource() },
    ).flow.map {
        it.map { entity ->
            when (entity.source) {
                Source.WALLHAVEN -> {
                    val wallpaperEntity = wallhavenWallpapersDao.getByWallhavenId(entity.sourceId)
                    wallpaperEntity?.toWallpaper() ?: wallhavenWallpaper1
                }
                Source.REDDIT -> {
                    val wallpaperEntity = redditWallpapersDao.getByRedditId(entity.sourceId)
                    wallpaperEntity?.toWallpaper() ?: wallhavenWallpaper1
                }
                Source.LOCAL -> localWallpapersRepository.wallpaper(
                    context = context,
                    wallpaperUriStr = entity.sourceId,
                ).firstOrNull()?.successOr(null) ?: wallhavenWallpaper1
            }
        }.filter { wallpaper -> wallpaper != wallhavenWallpaper1 }
    }.flowOn(ioDispatcher)

    suspend fun upsert(
        sourceId: String,
        source: Source,
        typeFlags: Int,
    ) = withContext(ioDispatcher) {
        if (typeFlags.isUnspecified()) {
            // delete if set to unspecified
            delete(sourceId, source)
            return@withContext
        }
        val existing = lightDarkDao.getBySourceIdAndSource(
            sourceId = sourceId,
            source = source,
        )
        val updatedOn = Clock.System.now()
        val updated = existing?.copy(
            updatedOn = updatedOn,
            typeFlags = typeFlags,
        ) ?: LightDarkEntity(
            id = 0,
            sourceId = sourceId,
            source = source,
            typeFlags = typeFlags,
            updatedOn = updatedOn,
        )
        lightDarkDao.upsert(updated)
    }

    suspend fun delete(
        sourceId: String,
        source: Source,
    ) = withContext(ioDispatcher) {
        lightDarkDao.deleteBySourceIdAndSource(
            sourceId = sourceId,
            source = source,
        )
    }

    fun observeIsFavorite(
        source: Source,
        sourceId: String,
    ) = lightDarkDao.observeTypeFlags(source = source, sourceId = sourceId)

    // suspend fun insertEntities(entities: Collection<ViewedEntity>) = withContext(ioDispatcher) {
    //     val existing = lightDarkDao.getAll()
    //     val existingMap = existing.associateBy { (it.source to it.sourceId) }
    //     val insert = entities.filter {
    //         // only take non-existing
    //         existingMap[(it.source to it.sourceId)] == null
    //     }.map {
    //         // reset id
    //         it.copy(id = 0)
    //     }
    //     lightDarkDao.insertAll(insert)
    // }
}
