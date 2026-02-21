package dev.dimension.flare.ui.presenter

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    val lastItemCount: Int = 0,
    val lastPrependState: LoadState = LoadState.NotLoading(endOfPaginationReached = false),
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

        var lvpState by remember { mutableStateOf(LvpState()) }
        var newPostsState by remember { mutableStateOf(NewPostsState()) }
        var hasRestoredScroll by remember { mutableStateOf(false) }

        // LVP (Last Viewed Post) management
        val scrollPositionRepo = koinInject<ScrollPositionRepository>()

        LaunchedEffect(state.listState) {
            state.listState.onSuccess {
                // Restore scroll position on initial load and after refresh completes
                launch {
                    snapshotFlow { refreshState }
                        .distinctUntilChanged()
                        .collect { currentRefreshState ->
                            if (currentRefreshState is LoadState.Loading) {
                                hasRestoredScroll = false
                            }

                            val shouldRestore =
                                (currentRefreshState !is LoadState.Loading) &&
                                    (itemCount > 0) &&
                                    !hasRestoredScroll

                            if (shouldRestore) {
                                lvpState = lvpState.copy(status = LvpState.Status.RESTORING)
                                try {
                                    val scrollPosition = scrollPositionRepo.getScrollPosition(timelineTabItem.key)
                                    val lvpIndex = restoreLvpInFeed(lazyListState, this@onSuccess, scrollPosition)

                                    if (lvpIndex >= 0) {
                                        if (lvpIndex > 0) {
                                            newPostsState = newPostsState.copy(showIndicator = true, count = lvpIndex)
                                            println("[$LVP_LOG_TAG] New posts indicator triggered: $lvpIndex posts above LVP")
                                        }
                                        lvpState = lvpState.copy(status = LvpState.Status.IDLE)
                                    } else if (scrollPosition != null) {
                                        lvpState = lvpState.copy(status = LvpState.Status.NOT_FOUND, lastObservedItemCount = itemCount)
                                        println("[$LVP_LOG_TAG] LVP not found, monitoring itemCount to detect when paging stops")
                                    } else {
                                        lvpState = lvpState.copy(status = LvpState.Status.IDLE)
                                    }
                                    hasRestoredScroll = true
                                } catch (e: Exception) {
                                    val errorContext = if (!hasRestoredScroll) "initial load" else "refresh"
                                    println("[$LVP_LOG_TAG] Error restoring LVP on $errorContext: ${e.message}")
                                    hasRestoredScroll = true
                                    lvpState = lvpState.copy(status = LvpState.Status.IDLE)
                                }
                            }
                        }
                }

                // Monitor itemCount to detect when paging has stopped
                launch {
                    snapshotFlow { itemCount }
                        .collect { currentItemCount ->
                            if (lvpState.status == LvpState.Status.NOT_FOUND) {
                                if (currentItemCount > lvpState.lastObservedItemCount) {
                                    lvpState = lvpState.copy(lastObservedItemCount = currentItemCount)
                                    println("[$LVP_LOG_TAG] ItemCount grew to $currentItemCount, paging still active")
                                } else if (currentItemCount == lvpState.lastObservedItemCount && refreshState !is LoadState.Loading) {
                                    lvpRestoreFailedEventsSource.tryEmit(Unit)
                                    println(
                                        "[$LVP_LOG_TAG] ItemCount stable at $currentItemCount and not refreshing - paging stopped, LVP not found, showing notification",
                                    )
                                    lvpState = lvpState.copy(status = LvpState.Status.IDLE)
                                }
                            }
                        }
                }
            }
        }

        var lastSavedIndex by remember { mutableStateOf(-1) }
        var feedInitiallyLoaded by remember { mutableStateOf(false) }

        // Save scroll position when the list is in Success state.
        LaunchedEffect(lazyListState, state.listState) {
            snapshotFlow {
                val visibleItems = lazyListState.layoutInfo.visibleItemsInfo
                if (visibleItems.isEmpty()) {
                    -1
                } else {
                    val viewportTop = 0
                    visibleItems.firstOrNull { it.offset.y >= viewportTop }?.index ?: visibleItems.firstOrNull()?.index ?: -1
                }
            }.distinctUntilChanged()
                .collect { index ->
                    val successState = state.listState as? TimelineSuccess ?: return@collect
                    if (!feedInitiallyLoaded && index >= 0) {
                        feedInitiallyLoaded = true
                        return@collect
                    }
                    if (index < 0 || index == lastSavedIndex) return@collect

                    try {
                        val item = successState.peek(index)
                        if (item != null) {
                            val status = item.content as? StatusContent ?: return@collect
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
                            lastSavedIndex = index
                        }
                    } catch (e: Exception) {
                        // Silently fail
                    }
                }
        }

        // Detect when new posts have been loaded at the top during a refresh
        state.listState.onSuccess {
            LaunchedEffect(this) {
                snapshotFlow { Triple(prependState, appendState, itemCount) }
                    .distinctUntilChanged()
                    .collect { (currentPrependState, currentAppendState, currentItemCount) ->
                        val visibleIndex = lazyListState.firstVisibleItemIndex
                        if (newPostsState.lastPrependState is LoadState.Loading &&
                            currentPrependState is LoadState.NotLoading &&
                            currentItemCount > newPostsState.lastItemCount &&
                            visibleIndex > 0 &&
                            hasRestoredScroll
                        ) {
                            val newCount = currentItemCount - newPostsState.lastItemCount
                            val totalNewAbove = newPostsState.count + newCount
                            newPostsState = newPostsState.copy(showIndicator = true, count = totalNewAbove)
                        }
                        newPostsState = newPostsState.copy(lastItemCount = currentItemCount, lastPrependState = currentPrependState)
                    }
            }
        }

        val isAtTheTop by remember {
            derivedStateOf {
                val firstItem = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
                if (firstItem != null) {
                    // Check if more than 50% of the first item is visible
                    // offset.y is negative when the top of the item is scrolled off-screen
                    firstItem.offset.y > -(firstItem.size.height / 2)
                } else {
                    lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset == 0
                }
            }
        }

        // Sync newPostsCount with scroll position (decrement as user scrolls up)
        LaunchedEffect(lazyListState.firstVisibleItemIndex) {
            if (newPostsState.showIndicator && lazyListState.firstVisibleItemIndex < newPostsState.count) {
                val newCount = lazyListState.firstVisibleItemIndex
                newPostsState =
                    newPostsState.copy(
                        count = newCount,
                    )
            }
        }

        LaunchedEffect(isAtTheTop, newPostsState.showIndicator) {
            if (isAtTheTop) {
                newPostsState = newPostsState.copy(showIndicator = false, count = 0)
            } else if (!newPostsState.showIndicator) {
                newPostsState = newPostsState.copy(count = 0)
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
        }
    }
}
