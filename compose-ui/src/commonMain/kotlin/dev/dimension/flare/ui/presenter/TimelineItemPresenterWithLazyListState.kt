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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private const val LVP_LOG_TAG = "LVP_REFRESH"

private typealias TimelineSuccess = dev.dimension.flare.common.PagingState.Success<dev.dimension.flare.ui.model.UiTimeline>
private typealias StatusContent = dev.dimension.flare.ui.model.UiTimeline.ItemContent.Status

private data class LvpState(
    val status: Status = Status.IDLE,
    val lastObservedItemCount: Int = 0,
) {
    enum class Status {
        IDLE,
        RESTORING,
        NOT_FOUND,
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
        println("[$LVP_LOG_TAG] Fetched $itemCount posts for ${timelineTabItem.key}")

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

        var lvpState by remember { mutableStateOf(LvpState()) }
        var newPostsState by remember { mutableStateOf(NewPostsState()) }
        var hasRestoredScroll by remember { mutableStateOf(false) }

        // LVP (Last Viewed Post) management
        val scrollPositionRepo = koinInject<ScrollPositionRepository>()

        val currentVisibleIndexState = remember {
            derivedStateOf {
                val topItem = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull()
                // Ensure layoutInfo is in sync with the actual scroll position to avoid race conditions during refreshes
                if (topItem != null && topItem.index == lazyListState.firstVisibleItemIndex) {
                    // Item is "effectively" visible if it's more than 50% in view
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
        val currentVisibleIndex by currentVisibleIndexState

        val saveCurrentScrollPosition = {
            // Use the actual first visible item index for LVP saving, ignoring the 50% indicator logic.
            val index = lazyListState.firstVisibleItemIndex
            if (index >= 0) {
                val successState = state.listState as? TimelineSuccess
                successState?.peek(index)?.let { item ->
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

        LaunchedEffect(state.listState) {
            state.listState.onSuccess {
                val successState = this
                // Restore scroll position on initial load and after refresh completes
                launch {
                    snapshotFlow { Triple(refreshState, itemCount, appendState) }
                        .distinctUntilChanged()
                        .collect { (currentRefreshState, currentItemCount, currentAppendState) ->
                            if (currentRefreshState is LoadState.Loading) {
                                hasRestoredScroll = false
                                lvpState = lvpState.copy(status = LvpState.Status.IDLE)
                                return@collect
                            }

                            val shouldRestore =
                                (currentItemCount > 0) &&
                                    !hasRestoredScroll &&
                                    (lvpState.status == LvpState.Status.IDLE || lvpState.status == LvpState.Status.NOT_FOUND)

                            if (shouldRestore) {
                                // If NOT_FOUND, only re-scan if itemCount grew to avoid busy loops
                                if (lvpState.status == LvpState.Status.NOT_FOUND && currentItemCount <= lvpState.lastObservedItemCount) {
                                    // Check if we reached the end of the feed
                                    if (currentAppendState is LoadState.NotLoading && currentAppendState.endOfPaginationReached) {
                                        lvpRestoreFailedEventsSource.tryEmit(Unit)
                                        hasRestoredScroll = true
                                        lvpState = lvpState.copy(status = LvpState.Status.IDLE)
                                    }
                                    return@collect
                                }

                                lvpState = lvpState.copy(status = LvpState.Status.RESTORING)
                                try {
                                    val scrollPosition = scrollPositionRepo.getScrollPosition(timelineTabItem.key)
                                    if (scrollPosition == null) {
                                        hasRestoredScroll = true
                                        lvpState = lvpState.copy(status = LvpState.Status.IDLE)
                                        return@collect
                                    }

                                    val lvpIndex = restoreLvpInFeed(lazyListState, successState, scrollPosition)

                                    if (lvpIndex >= 0) {
                                        if (lvpIndex > 0) {
                                            newPostsState = newPostsState.copy(showIndicator = true, count = lvpIndex)
                                            println("[$LVP_LOG_TAG] New posts indicator triggered: $lvpIndex posts above LVP")
                                        }
                                        lvpState = lvpState.copy(status = LvpState.Status.IDLE)
                                        hasRestoredScroll = true
                                    } else {
                                        lvpState = lvpState.copy(status = LvpState.Status.NOT_FOUND, lastObservedItemCount = currentItemCount)
                                        println("[$LVP_LOG_TAG] LVP not found, monitoring itemCount to detect when paging stops")
                                        
                                        // Final check for end of road
                                        if (currentAppendState is LoadState.NotLoading && currentAppendState.endOfPaginationReached) {
                                            lvpRestoreFailedEventsSource.tryEmit(Unit)
                                            hasRestoredScroll = true
                                            lvpState = lvpState.copy(status = LvpState.Status.IDLE)
                                        }
                                    }
                                } catch (e: Exception) {
                                    val errorContext = if (!hasRestoredScroll) "initial load" else "refresh"
                                    println("[$LVP_LOG_TAG] Error restoring LVP on $errorContext: ${e.message}")
                                    hasRestoredScroll = true
                                    lvpState = lvpState.copy(status = LvpState.Status.IDLE)
                                }
                            }
                        }
                }
            }
        }

        var lastItemCount by remember { mutableStateOf(0) }
        var lastPrependState by remember { mutableStateOf<LoadState>(LoadState.NotLoading(endOfPaginationReached = false)) }

        // Detect when new posts have been loaded at the top during a refresh
        state.listState.onSuccess {
            LaunchedEffect(this) {
                snapshotFlow { Triple(prependState, appendState, itemCount) }
                    .distinctUntilChanged()
                    .collect { (currentPrependState, currentAppendState, currentItemCount) ->
                        val visibleIndex = currentVisibleIndexState.value
                        if (lastPrependState is LoadState.Loading &&
                            currentPrependState is LoadState.NotLoading &&
                            currentItemCount > lastItemCount &&
                            visibleIndex > 0 &&
                            hasRestoredScroll
                        ) {
                            val newCount = currentItemCount - lastItemCount
                            val totalNewAbove = newPostsState.count + newCount
                            newPostsState = newPostsState.copy(showIndicator = true, count = totalNewAbove)
                        }
                        lastItemCount = currentItemCount
                        lastPrependState = currentPrependState
                    }
            }
        }

        // Sync newPostsCount with scroll position (decrement once 50% in view)
        LaunchedEffect(currentVisibleIndex, hasRestoredScroll) {
            if (hasRestoredScroll && newPostsState.showIndicator && currentVisibleIndex < newPostsState.count) {
                newPostsState =
                    newPostsState.copy(
                        count = currentVisibleIndex,
                    )
            }
        }

        LaunchedEffect(currentVisibleIndex, newPostsState.showIndicator, hasRestoredScroll) {
            if (hasRestoredScroll) {
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
                get() = state.isRefreshing || lvpState.status == LvpState.Status.RESTORING

            override fun onNewTootsShown() {
                newPostsState = newPostsState.copy(showIndicator = false)
            }

            override fun refreshSync() {
                saveCurrentScrollPosition()
                state.refreshSync()
            }

            override suspend fun refreshSuspend() {
                saveCurrentScrollPosition()
                state.refreshSuspend()
            }
        }
    }
}
