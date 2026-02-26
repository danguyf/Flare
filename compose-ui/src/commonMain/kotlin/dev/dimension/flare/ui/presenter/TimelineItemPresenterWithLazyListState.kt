package dev.dimension.flare.ui.presenter

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.paging.LoadState
import dev.dimension.flare.common.onSuccess
import dev.dimension.flare.data.database.scroll.model.DbFeedScrollPosition
import dev.dimension.flare.data.model.TimelineTabItem
import dev.dimension.flare.data.repository.ScrollPositionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private const val LVP_LOG_TAG = "LVP_REFRESH"
private const val LVP_RESTORE_LIMIT = 750

private typealias TimelineSuccess = dev.dimension.flare.common.PagingState.Success<dev.dimension.flare.ui.model.UiTimeline>
private typealias StatusContent = dev.dimension.flare.ui.model.UiTimeline.ItemContent.Status

private data class RestorationState(
    val status: Status = Status.READY,
    val lastObservedItemCount: Int = 0,
) {
    enum class Status {
        READY,
        RESTORING,
        NOT_FOUND,
        COMPLETED,
    }
}

private data class NewPostsState(
    val showIndicator: Boolean = false,
    val count: Int = 0,
)

public class TimelineItemPresenterWithLazyListState(
    private val timelineTabItem: TimelineTabItem,
    private val lazyStaggeredGridState: LazyStaggeredGridState? = null,
) : PresenterBase<TimelineItemPresenterWithLazyListState.State>() {
    @Immutable
    public interface State : TimelineItemPresenter.State {
        public val showNewToots: Boolean
        public val lazyListState: LazyStaggeredGridState
        public val newPostsCount: Int
        public val lvpRestoreFailedEvents: kotlinx.coroutines.flow.Flow<Unit>

        public fun onNewTootsShown()
    }

    private val tabItemPresenter by lazy {
        TimelineItemPresenter(timelineTabItem)
    }

    private val lvpRestoreFailedEventsSource: kotlinx.coroutines.flow.MutableSharedFlow<Unit> =
        kotlinx.coroutines.flow.MutableSharedFlow(extraBufferCapacity = 1)

    private suspend fun restoreLvpInFeed(
        lazyListState: LazyStaggeredGridState,
        successState: TimelineSuccess,
        scrollPosition: DbFeedScrollPosition?,
    ): Int {
        val itemCount = successState.itemCount
        println("[$LVP_LOG_TAG] Checking $itemCount posts for ${timelineTabItem.key}")

        val sortId = scrollPosition?.lastViewedSortId ?: return -1.also { println("[$LVP_LOG_TAG] No saved LVP found") }
        val statusKey = scrollPosition.lastViewedStatusKey
        if (sortId <= 0 || statusKey == null) return -1

        val foundIndex =
            (0 until itemCount).firstOrNull { i ->
                (successState.peek(i)?.content as? StatusContent)?.statusKey == statusKey
            } ?: -1

        return if (foundIndex >= 0) {
            println("[$LVP_LOG_TAG] LVP found at index=$foundIndex, scrolling to it")
            lazyListState.scrollToItem(foundIndex, scrollOffset = 0)
            foundIndex
        } else {
            println("[$LVP_LOG_TAG] LVP NOT found in $itemCount loaded posts, scrolling to bottom to trigger older post loading")
            if (itemCount > 0) lazyListState.scrollToItem(itemCount - 1)
            -1
        }
    }

    @Composable
    override fun body(): State {
        val lazyListState = lazyStaggeredGridState ?: rememberLazyStaggeredGridState()
        val state = tabItemPresenter.body()
        val scope = rememberCoroutineScope()

        var restorationState by remember { mutableStateOf(RestorationState()) }
        var newPostsState by remember { mutableStateOf(NewPostsState()) }
        var lastObservedIndicatorItemCount by remember { mutableStateOf(0) }

        // LVP (Last Viewed Post) management
        val scrollPositionRepo = koinInject<ScrollPositionRepository>()

        // 50% threshold logic strictly for the UI indicator count
        val currentVisibleIndexState = remember {
            derivedStateOf {
                val topItem = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull()
                if (topItem != null && topItem.index == lazyListState.firstVisibleItemIndex) {
                    if (topItem.offset.y <= -(topItem.size.height / 2)) {
                        topItem.index + 1
                    } else {
                        topItem.index
                    }
                } else {
                    lazyListState.firstVisibleItemIndex
                }
            }
        }

        val currentState by rememberUpdatedState(state)

        // Save logic uses precise top-of-post index
        val saveCurrentScrollPosition = {
            val index = lazyListState.firstVisibleItemIndex
            if (index >= 0) {
                (currentState.listState as? TimelineSuccess)?.peek(index)?.let { item ->
                    (item.content as? StatusContent)?.let { status ->
                        scope.launch {
                            scrollPositionRepo.saveScrollPosition(
                                DbFeedScrollPosition(
                                    pagingKey = timelineTabItem.key,
                                    lastViewedStatusKey = status.statusKey,
                                    lastViewedSortId = status.createdAt.value.toEpochMilliseconds(),
                                    lastUpdated =
                                        kotlin.time.Clock.System
                                            .now()
                                            .toEpochMilliseconds(),
                                ),
                            )
                        }
                    }
                }
            }
        }

        val currentSaveFunction by rememberUpdatedState(saveCurrentScrollPosition)

        DisposableEffect(Unit) {
            onDispose {
                currentSaveFunction()
            }
        }

        // Unified restoration logic keyed by tab key to avoid stale state and cancellation loops
        LaunchedEffect(timelineTabItem.key) {
            println("[$LVP_LOG_TAG] Restoration monitoring started for ${timelineTabItem.key}")
            snapshotFlow {
                val s = currentState
                val ls = s.listState as? TimelineSuccess
                if (ls != null) {
                    ls to Triple(ls.itemCount, s.isRefreshing, ls.appendState)
                } else null
            }.collect { data ->
                val (ls, info) = data ?: run {
                    restorationState = RestorationState(status = RestorationState.Status.READY)
                    return@collect
                }
                val (itemCount, isRefreshing, appendState) = info

                if (isRefreshing) {
                    if (restorationState.status == RestorationState.Status.COMPLETED) {
                        println("[$LVP_LOG_TAG] Refresh detected, resetting restoration gate")
                        restorationState = RestorationState(status = RestorationState.Status.READY)
                        newPostsState = NewPostsState()
                        lastObservedIndicatorItemCount = 0
                    }
                    return@collect
                }

                if (restorationState.status == RestorationState.Status.COMPLETED || itemCount == 0) return@collect

                val giveUpSearch = { reason: String ->
                    println("[$LVP_LOG_TAG] $reason. Stopping search.")
                    lvpRestoreFailedEventsSource.tryEmit(Unit)
                    // Trigger New Posts indicator for items now above us
                    val visIndex = lazyListState.firstVisibleItemIndex
                    if (visIndex > 0) {
                        newPostsState = newPostsState.copy(showIndicator = true, count = visIndex)
                    }
                    restorationState = restorationState.copy(status = RestorationState.Status.COMPLETED)
                }

                // Limit check: if we've loaded too many posts without finding LVP, give up.
                if (itemCount > LVP_RESTORE_LIMIT && restorationState.status != RestorationState.Status.READY) {
                    giveUpSearch("LVP not found within $LVP_RESTORE_LIMIT posts")
                    return@collect
                }

                // Re-scan check: only re-run if itemCount grew to avoid busy loops
                if (restorationState.status == RestorationState.Status.NOT_FOUND && itemCount <= restorationState.lastObservedItemCount) {
                    if (appendState is LoadState.NotLoading && appendState.endOfPaginationReached) {
                        giveUpSearch("Feed exhausted, LVP not found")
                    }
                    return@collect
                }

                restorationState = restorationState.copy(status = RestorationState.Status.RESTORING)
                try {
                    val scrollPosition = scrollPositionRepo.getScrollPosition(timelineTabItem.key)
                    if (scrollPosition == null) {
                        println("[$LVP_LOG_TAG] No saved LVP found in DB")
                        restorationState = restorationState.copy(status = RestorationState.Status.COMPLETED)
                        return@collect
                    }

                    val lvpIndex = restoreLvpInFeed(lazyListState, ls, scrollPosition)
                    if (lvpIndex >= 0) {
                        if (lvpIndex > 0) {
                            newPostsState = newPostsState.copy(showIndicator = true, count = lvpIndex)
                            // Wait for the scroll to actually be reflected in the UI before completing
                            // to avoid the indicator-reset logic triggering while index is still 0.
                            snapshotFlow { currentVisibleIndexState.value }
                                .filter { it >= lvpIndex }
                                .first()
                        }
                        restorationState = restorationState.copy(status = RestorationState.Status.COMPLETED)
                    } else {
                        restorationState = restorationState.copy(status = RestorationState.Status.NOT_FOUND, lastObservedItemCount = itemCount)
                        if (appendState is LoadState.NotLoading && appendState.endOfPaginationReached) {
                            giveUpSearch("Feed exhausted, LVP not found")
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    println("[$LVP_LOG_TAG] Error during LVP restoration: ${e.message}")
                    restorationState = restorationState.copy(status = RestorationState.Status.COMPLETED)
                }
            }
        }

        var lastPrependState by remember { mutableStateOf<LoadState>(LoadState.NotLoading(endOfPaginationReached = false)) }

        // Detect when new posts have been loaded at the top during a refresh
        state.listState.onSuccess {
            LaunchedEffect(this) {
                snapshotFlow { Triple(prependState, appendState, itemCount) }
                    .distinctUntilChanged()
                    .collect { (currentPrependState, currentAppendState, currentItemCount) ->
                        val visibleIndex = currentVisibleIndexState.value
                        if (lastObservedIndicatorItemCount == 0) {
                            if (currentItemCount > 0) {
                                lastObservedIndicatorItemCount = currentItemCount
                            }
                        } else if (lastPrependState is LoadState.Loading &&
                            currentPrependState is LoadState.NotLoading &&
                            currentItemCount > lastObservedIndicatorItemCount &&
                            visibleIndex > 0 &&
                            restorationState.status == RestorationState.Status.COMPLETED
                        ) {
                            val newCount = currentItemCount - lastObservedIndicatorItemCount
                            val totalNewAbove = newPostsState.count + newCount
                            newPostsState = newPostsState.copy(showIndicator = true, count = totalNewAbove)
                        }
                        lastObservedIndicatorItemCount = currentItemCount
                        lastPrependState = currentPrependState
                    }
            }
        }

        // Sync newPostsCount with scroll position (decrement once 50% in view)
        LaunchedEffect(currentVisibleIndexState.value, restorationState.status) {
            val currentVisibleIndex = currentVisibleIndexState.value
            if (restorationState.status == RestorationState.Status.COMPLETED && newPostsState.showIndicator && currentVisibleIndex < newPostsState.count) {
                newPostsState =
                    newPostsState.copy(
                        count = currentVisibleIndex,
                    )
            }
        }

        LaunchedEffect(currentVisibleIndexState.value, newPostsState.showIndicator, restorationState.status) {
            val currentVisibleIndex = currentVisibleIndexState.value
            if (restorationState.status == RestorationState.Status.COMPLETED) {
                if (currentVisibleIndex == 0) {
                    newPostsState = newPostsState.copy(showIndicator = false, count = 0)
                } else if (!newPostsState.showIndicator) {
                    newPostsState = newPostsState.copy(count = 0)
                }
            }
        }

        return object : State, TimelineItemPresenter.State by state {
            override val showNewToots = newPostsState.showIndicator
            override val lazyListState = lazyListState
            override val newPostsCount = newPostsState.count
            override val lvpRestoreFailedEvents = lvpRestoreFailedEventsSource
            override val isRefreshing: Boolean
                get() = state.isRefreshing || restorationState.status == RestorationState.Status.RESTORING

            override fun onNewTootsShown() {
                newPostsState = newPostsState.copy(showIndicator = false)
            }

            override fun refreshSync() {
                saveCurrentScrollPosition()
                restorationState = RestorationState(status = RestorationState.Status.READY)
                newPostsState = NewPostsState()
                lastObservedIndicatorItemCount = 0
                state.refreshSync()
            }

            override suspend fun refreshSuspend() {
                saveCurrentScrollPosition()
                restorationState = RestorationState(status = RestorationState.Status.READY)
                newPostsState = NewPostsState()
                lastObservedIndicatorItemCount = 0
                state.refreshSuspend()
            }
        }
    }
}
