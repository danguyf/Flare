package dev.dimension.flare.common

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.database.cache.connect
import dev.dimension.flare.data.database.cache.mapper.saveToDatabase
import dev.dimension.flare.data.database.cache.model.DbPagingKey
import dev.dimension.flare.data.database.cache.model.DbPagingTimelineWithStatus
import dev.dimension.flare.data.database.scroll.ScrollPositionDatabase
import dev.dimension.flare.data.repository.DebugRepository
import dev.dimension.flare.ui.model.UiTimeline

@OptIn(ExperimentalPagingApi::class)
internal abstract class BaseRemoteMediator<Key : Any, Value : Any> : RemoteMediator<Key, Value>() {
    final override suspend fun load(
        loadType: LoadType,
        state: PagingState<Key, Value>,
    ): MediatorResult =
        try {
            doLoad(loadType, state)
        } catch (e: Throwable) {
            onError(e)
            DebugRepository.error(e)
            MediatorResult.Error(e)
        }

    abstract suspend fun doLoad(
        loadType: LoadType,
        state: PagingState<Key, Value>,
    ): MediatorResult

    protected open fun onError(e: Throwable) {
    }
}

internal sealed interface BaseTimelineLoader {
    data object NotSupported : BaseTimelineLoader
}

@OptIn(ExperimentalPagingApi::class)
internal abstract class BaseTimelineRemoteMediator(
    private val database: CacheDatabase,
    private val scrollPositionDatabase: ScrollPositionDatabase,
) : BaseRemoteMediator<Int, DbPagingTimelineWithStatus>(),
    BaseTimelineLoader {
    abstract val pagingKey: String

    final override suspend fun doLoad(
        loadType: LoadType,
        state: PagingState<Int, DbPagingTimelineWithStatus>,
    ): MediatorResult {
        val request: Request =
            when (loadType) {
                LoadType.REFRESH -> Request.Refresh
                LoadType.PREPEND -> {
                    val previousKey =
                        database.pagingTimelineDao().getPagingKey(pagingKey)?.prevKey
                            ?: return MediatorResult.Success(endOfPaginationReached = true)
                    Request.Prepend(previousKey)
                }
                LoadType.APPEND -> {
                    val nextKey =
                        database.pagingTimelineDao().getPagingKey(pagingKey)?.nextKey
                            ?: return MediatorResult.Success(endOfPaginationReached = true)
                    Request.Append(nextKey)
                }
            }

        val lvpStatusKey =
            if (loadType == LoadType.REFRESH) {
                scrollPositionDatabase.scrollPositionDao().getScrollPosition(pagingKey)?.lastViewedStatusKey
            } else {
                null
            }

        var result =
            timeline(
                pageSize = state.config.pageSize,
                request = request,
            )

        if (loadType == LoadType.REFRESH && lvpStatusKey != null) {
            var lvpFound = result.data.any { it.timeline.statusKey == lvpStatusKey }
            var nextKey = result.nextKey
            var endReached = result.endOfPaginationReached
            var searchIterations = 0
            val maxSearchIterations = 20
            val combinedData = result.data.toMutableList()

            while (!lvpFound && nextKey != null && searchIterations < maxSearchIterations) {
                searchIterations++
                val appendResult =
                    timeline(
                        pageSize = state.config.pageSize,
                        request = Request.Append(nextKey),
                    )

                if (appendResult.data.isEmpty()) {
                    endReached = appendResult.endOfPaginationReached
                    nextKey = appendResult.nextKey
                    break
                }

                combinedData.addAll(appendResult.data)
                lvpFound = appendResult.data.any { it.timeline.statusKey == lvpStatusKey }
                nextKey = appendResult.nextKey
                endReached = appendResult.endOfPaginationReached
            }

            if (combinedData.size != result.data.size) {
                result =
                    Result(
                        endOfPaginationReached = endReached,
                        data = combinedData,
                        nextKey = nextKey,
                        previousKey = result.previousKey,
                    )
            }
        }

        database.connect {
            if (loadType == LoadType.REFRESH) {
                result.data.groupBy { it.timeline.pagingKey }.keys.forEach { key ->
                    database
                        .pagingTimelineDao()
                        .delete(pagingKey = key)
                }
                database.pagingTimelineDao().deletePagingKey(pagingKey)
                database.pagingTimelineDao().insertPagingKey(
                    DbPagingKey(
                        pagingKey = pagingKey,
                        nextKey = result.nextKey,
                        prevKey = result.previousKey,
                    ),
                )
            } else if (loadType == LoadType.PREPEND && result.previousKey != null) {
                database.pagingTimelineDao().updatePagingKeyPrevKey(
                    pagingKey = pagingKey,
                    prevKey = result.previousKey,
                )
            } else if (loadType == LoadType.APPEND && result.nextKey != null) {
                database.pagingTimelineDao().updatePagingKeyNextKey(
                    pagingKey = pagingKey,
                    nextKey = result.nextKey,
                )
            }
            saveToDatabase(database, result.data)
        }
        return MediatorResult.Success(
            endOfPaginationReached =
                result.endOfPaginationReached ||
                    when (loadType) {
                        LoadType.REFRESH -> false
                        LoadType.PREPEND -> result.previousKey == null
                        LoadType.APPEND -> result.nextKey == null
                    },
        )
    }

    abstract suspend fun timeline(
        pageSize: Int,
        request: Request,
    ): Result

    data class Result(
        val endOfPaginationReached: Boolean,
        val data: List<DbPagingTimelineWithStatus> = emptyList(),
        val nextKey: String? = null,
        val previousKey: String? = null,
    )

    sealed interface Request {
        data object Refresh : Request

        data class Prepend(
            val previousKey: String,
        ) : Request

        data class Append(
            val nextKey: String,
        ) : Request
    }
}

internal fun interface BaseTimelinePagingSourceFactory<T : Any> : BaseTimelineLoader {
    abstract fun create(): BasePagingSource<T, UiTimeline>
}
