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
import dev.dimension.flare.common.onSuccess
import dev.dimension.flare.data.database.scroll.model.DbFeedScrollPosition
import dev.dimension.flare.data.model.TimelineTabItem
import dev.dimension.flare.data.repository.ScrollPositionRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private const val LVP_LOG_TAG = "LVP_REFRESH"

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

    private val _lvpRestoreFailedEvents: kotlinx.coroutines.flow.MutableSharedFlow<Unit> =
        kotlinx.coroutines.flow.MutableSharedFlow(extraBufferCapacity = 1)

    private suspend fun restoreLvpInFeed(
        lazyListState: LazyStaggeredGridState,
        successState: dev.dimension.flare.common.PagingState.Success<dev.dimension.flare.ui.model.UiTimeline>,
        scrollPosition: DbFeedScrollPosition?,
    ): Int {
        // Returns the LVP index if found (to trigger "New Posts" indicator), or -1 if not found
        val itemCount = successState.itemCount
        println("[$LVP_LOG_TAG] Fetched $itemCount posts for ${timelineTabItem.key}")
        if (scrollPosition != null) {
            val sortId = scrollPosition.lastViewedSortId
            val statusKey = scrollPosition.lastViewedStatusKey
            println("[$LVP_LOG_TAG] Saved LVP: statusKey=$statusKey, sortId=$sortId")
            if (sortId != null && sortId > 0 && statusKey != null) {
                // Search through currently loaded items for the saved status
                var foundIndex = -1
                for (i in 0 until itemCount) {
                    val item = successState.peek(i)
                    if (item != null) {
                        val itemStatusKey =
                            when (val content = item.content) {
                                is dev.dimension.flare.ui.model.UiTimeline.ItemContent.Status -> content.statusKey
                                else -> null
                            }
                        if (itemStatusKey == statusKey) {
                            foundIndex = i
                            break
                        }
                    }
                }

                if (foundIndex >= 0) {
                    println("[$LVP_LOG_TAG] LVP found at index=$foundIndex, scrolling to it")
                    lazyListState.scrollToItem(foundIndex, scrollOffset = 0)
                    return foundIndex
                } else {
                    println(
                        "[$LVP_LOG_TAG] LVP NOT found in $itemCount loaded posts, scrolling to bottom to trigger older post loading",
                    )
                    // Scroll to bottom to trigger loading of older posts
                    if (itemCount > 0) {
                        lazyListState.scrollToItem(itemCount - 1)
                    }
                    return -1
                }
            }
        } else {
            println("[$LVP_LOG_TAG] No saved LVP found")
        }
        return -1
    }

    @Composable
    override fun body(): State {
        val lazyListState = lazyStaggeredGridState ?: rememberLazyStaggeredGridState()
        val state = tabItemPresenter.body()
        var showNewToots by remember { mutableStateOf(false) }
        var lastRefreshIndex by remember { mutableStateOf(0) }
        var newPostCount by remember { mutableStateOf(0) }
        var hasRestoredScroll by remember { mutableStateOf(false) }
        var lvpRestoreInProgress by remember { mutableStateOf(false) }
        var lvpNotFound by remember { mutableStateOf(false) }
        var lastObservedItemCount by remember { mutableStateOf(0) }

        // LVP (Last Viewed Post) management
        val scrollPositionRepo = koinInject<ScrollPositionRepository>()

        LaunchedEffect(state.listState) {
            state.listState.onSuccess {
                // Restore scroll position on initial load and after refresh completes
                launch {
                    snapshotFlow { isRefreshing }
                        .distinctUntilChanged()
                        .collect { currentlyRefreshing ->
                            // Restore LVP on initial load (when hasRestoredScroll is false and we have items)
                            // or when refresh completes (when currentlyRefreshing becomes false)
                            val shouldRestore =
                                (!hasRestoredScroll && itemCount > 0) ||
                                    (!currentlyRefreshing && itemCount > 0 && hasRestoredScroll)

                            if (shouldRestore) {
                                lvpRestoreInProgress = true
                                try {
                                    val scrollPosition =
                                        scrollPositionRepo.getScrollPosition(timelineTabItem.key)
                                    val lvpIndex = restoreLvpInFeed(
                                        lazyListState,
                                        this@onSuccess,
                                        scrollPosition,
                                    )
                                    // If LVP was found at index > 0, trigger "New Posts" indicator
                                    if (lvpIndex > 0) {
                                        showNewToots = true
                                        newPostCount = lvpIndex
                                        println("[$LVP_LOG_TAG] New posts indicator triggered: $lvpIndex posts above LVP")
                                        lvpNotFound = false
                                    } else if (lvpIndex < 0 && scrollPosition != null && hasRestoredScroll) {
                                        // LVP was saved but not found - mark for monitoring
                                        lvpNotFound = true
                                        lastObservedItemCount = itemCount
                                        println("[$LVP_LOG_TAG] LVP not found, monitoring itemCount to detect when paging stops")
                                    }
                                    hasRestoredScroll = true
                                } catch (e: Exception) {
                                    val errorContext = if (!hasRestoredScroll) "initial load" else "refresh"
                                    println("[$LVP_LOG_TAG] Error restoring LVP on $errorContext: ${e.message}")
                                    hasRestoredScroll = true
                                } finally {
                                    lvpRestoreInProgress = false
                                }
                            } else if (!currentlyRefreshing) {
                                lvpRestoreInProgress = false
                            }
                        }
                }

                // Monitor itemCount to detect when paging has stopped
                launch {
                    snapshotFlow { itemCount }
                        .collect { currentItemCount ->
                            if (lvpNotFound) {
                                if (currentItemCount > lastObservedItemCount) {
                                    // ItemCount increased - more pages loaded, keep waiting
                                    lastObservedItemCount = currentItemCount
                                    println("[$LVP_LOG_TAG] ItemCount grew to $currentItemCount, paging still active")
                                } else if (currentItemCount == lastObservedItemCount && !isRefreshing) {
                                    // ItemCount hasn't changed and not refreshing - paging has stopped
                                    _lvpRestoreFailedEvents.tryEmit(Unit)
                                    println("[$LVP_LOG_TAG] ItemCount stable at $currentItemCount and not refreshing - paging stopped, LVP not found, showing notification")
                                    lvpNotFound = false
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
                // Find the topmost FULLY visible item (not partially obscured)
                val visibleItems = lazyListState.layoutInfo.visibleItemsInfo
                if (visibleItems.isEmpty()) {
                    -1
                } else {
                    // Find the first item where the top is visible (not cut off at viewport top)
                    val viewportTop = 0
                    visibleItems
                        .firstOrNull { itemInfo ->
                            itemInfo.offset.y >= viewportTop
                        }?.index ?: visibleItems.firstOrNull()?.index ?: -1
                }
            }.distinctUntilChanged()
                .collect { index ->
                    val successState = state.listState as? dev.dimension.flare.common.PagingState.Success
                    if (successState == null) {
                        return@collect
                    }

                    // Mark that the feed has loaded
                    if (!feedInitiallyLoaded && index >= 0) {
                        feedInitiallyLoaded = true
                        return@collect
                    }

                    // Only save if user actively scrolled (index changed from last saved)
                    if (index < 0 || index == lastSavedIndex) {
                        return@collect
                    }
                    try {
                        val item = successState.peek(index)
                        if (item != null) {
                            val statusKey =
                                when (val content = item.content) {
                                    is dev.dimension.flare.ui.model.UiTimeline.ItemContent.Status ->
                                        content.statusKey
                                    else -> null
                                }
                            if (statusKey != null) {
                                // Extract sortId directly from the post's createdAt timestamp
                                val sortId =
                                    when (val content = item.content) {
                                        is dev.dimension.flare.ui.model.UiTimeline.ItemContent.Status ->
                                            content.createdAt.value.toEpochMilliseconds()
                                        else -> null
                                    }
                                if (sortId != null && sortId > 0) {
                                    scrollPositionRepo.saveScrollPosition(
                                        DbFeedScrollPosition(
                                            pagingKey = timelineTabItem.key,
                                            lastViewedStatusKey = statusKey,
                                            lastViewedSortId = sortId,
                                            lastUpdated =
                                                kotlin.time.Clock.System
                                                    .now()
                                                    .toEpochMilliseconds(),
                                        ),
                                    )
                                    lastSavedIndex = index
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Silently fail - saving scroll position is not critical
                    }
                }
        }

        // Detect when new posts have been loaded at the top during a refresh
        state.listState.onSuccess {
            LaunchedEffect(Unit) {
                snapshotFlow { isRefreshing }
                    .distinctUntilChanged()
                    .collect { currentlyRefreshing ->
                        // When refresh completes, check if items were added above current position
                        if (!currentlyRefreshing && !lazyListState.isScrollInProgress) {
                            val currentIndex = lazyListState.firstVisibleItemIndex
                            // If we're not at the very top and new items were added, show the indicator
                            if (currentIndex > 0 && lastRefreshIndex < currentIndex) {
                                showNewToots = true
                                newPostCount = currentIndex
                            }
                        }
                    }
            }
        }

        // Detect when new posts have been loaded at the top during a refresh
        state.listState.onSuccess {
            LaunchedEffect(Unit) {
                snapshotFlow { Pair(itemCount, lazyListState.firstVisibleItemIndex) }
                    .distinctUntilChanged()
                    .collect { (currentItemCount, visibleIndex) ->
                        // When itemCount increases and we're not at the top, new posts were added above
                        if (currentItemCount > lastRefreshIndex && visibleIndex > 0 && hasRestoredScroll) {
                            showNewToots = true
                            newPostCount = currentItemCount - lastRefreshIndex
                            println("[$LVP_LOG_TAG] New posts detected: itemCount grew from $lastRefreshIndex to $currentItemCount while at index $visibleIndex")
                        }
                        lastRefreshIndex = currentItemCount
                    }
            }
        }
        val isAtTheTop by remember {
            derivedStateOf {
                lazyListState.firstVisibleItemIndex == 0 &&
                    lazyListState.firstVisibleItemScrollOffset == 0
            }
        }
        LaunchedEffect(isAtTheTop, showNewToots) {
            if (isAtTheTop) {
                showNewToots = false
            }
        }
        LaunchedEffect(showNewToots) {
            if (!showNewToots) {
                newPostCount = 0
            }
        }
        return object : State, TimelineItemPresenter.State by state {
            override val showNewToots = showNewToots
            override val lazyListState = lazyListState
            override val newPostsCount = newPostCount
            override val lvpRestoreFailedEvents = _lvpRestoreFailedEvents
            override val isRefreshing: Boolean
                get() = state.isRefreshing || lvpRestoreInProgress

            override fun onNewTootsShown() {
                showNewToots = false
            }
        }
    }
}
