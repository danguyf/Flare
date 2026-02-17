package dev.dimension.flare.ui.presenter

import android.util.Log
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
import dev.dimension.flare.data.database.cache.model.DbFeedScrollPosition
import dev.dimension.flare.data.model.TimelineTabItem
import dev.dimension.flare.data.repository.ScrollPositionRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.mapNotNull
import org.koin.compose.koinInject

private const val TAG = "LVP_DEBUG"

public class TimelineItemPresenterWithLazyListState(
    private val timelineTabItem: TimelineTabItem,
    private val lazyStaggeredGridState: LazyStaggeredGridState? = null,
) : PresenterBase<TimelineItemPresenterWithLazyListState.State>() {
    @Immutable
    public interface State : TimelineItemPresenter.State {
        public val showNewToots: Boolean
        public val lazyListState: LazyStaggeredGridState
        public val newPostsCount: Int

        public fun onNewTootsShown()
    }

    private val tabItemPresenter by lazy {
        TimelineItemPresenter(timelineTabItem)
    }

    @Composable
    override fun body(): State {
        Log.d(TAG, "TimelineItemPresenterWithLazyListState.body() called for ${timelineTabItem.key}")
        val lazyListState = lazyStaggeredGridState ?: rememberLazyStaggeredGridState()
        val state = tabItemPresenter.body()
        var showNewToots by remember { mutableStateOf(false) }
        var lastRefreshIndex by remember { mutableStateOf(0) }
        var newPostCount by remember { mutableStateOf(0) }
        var hasRestoredScroll by remember { mutableStateOf(false) }
        var lastItemCount by remember { mutableStateOf(0) }

        // LVP (Last Viewed Post) management
        val scrollPositionRepo = koinInject<ScrollPositionRepository>()

        // Restore scroll position on initial load
        LaunchedEffect(state.listState) {
            Log.d(TAG, "Restore LaunchedEffect triggered for ${timelineTabItem.key}")
            state.listState.onSuccess {
                Log.d(TAG, "State is Success, itemCount=$itemCount, hasRestoredScroll=$hasRestoredScroll")
                if (!hasRestoredScroll && itemCount > 0) {
                    // Only try to restore if items have been loaded
                    try {
                        val scrollPosition =
                            scrollPositionRepo.getScrollPosition(timelineTabItem.key)
                        if (scrollPosition != null) {
                            val sortId = scrollPosition.lastViewedSortId
                            val statusKey = scrollPosition.lastViewedStatusKey
                            Log.d(TAG, "Found scroll position: sortId=$sortId, statusKey=$statusKey, itemCount=$itemCount")
                            if (sortId != null && sortId > 0 && statusKey != null) {
                                // Search through currently loaded items for the saved status
                                Log.d(TAG, "LVP Restore: Searching for saved post in $itemCount loaded items...")
                                var foundIndex = -1
                                for (i in 0 until itemCount) {
                                    val item = peek(i)
                                    if (item != null) {
                                        val itemStatusKey = when (val content = item.content) {
                                            is dev.dimension.flare.ui.model.UiTimeline.ItemContent.Status -> content.statusKey
                                            else -> null
                                        }
                                        val itemSortId = when (val content = item.content) {
                                            is dev.dimension.flare.ui.model.UiTimeline.ItemContent.Status -> content.createdAt.value.toEpochMilliseconds()
                                            else -> null
                                        }
                                        if (itemStatusKey == statusKey) {
                                            foundIndex = i
                                            Log.d(TAG, "LVP Restore: Found saved post at index=$i, itemSortId=$itemSortId (saved sortId=$sortId)")
                                            break
                                        }
                                    }
                                }

                                if (foundIndex >= 0) {
                                    Log.d(TAG, "LVP Restore: Will scroll to index=$foundIndex")
                                    // Use scrollToItem with a small offset to ensure the post is fully visible
                                    // rather than positioned at the very edge
                                    lazyListState.scrollToItem(foundIndex, scrollOffset = 0)
                                    Log.d(TAG, "LVP Restore: ✓ Successfully restored to index=$foundIndex")
                                } else {
                                    Log.d(TAG, "LVP Restore: Saved post not found in currently loaded items, may need to load older posts")
                                    // Scroll to bottom to trigger loading of older posts
                                    if (itemCount > 0) {
                                        lazyListState.scrollToItem(itemCount - 1)
                                        Log.d(TAG, "LVP Restore: Scrolled to bottom to trigger loading of older posts")
                                    }
                                }
                            }
                        } else {
                            Log.d(TAG, "No saved scroll position found")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception during restore: ${e.message}", e)
                    }
                    hasRestoredScroll = true
                    lastItemCount = itemCount
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
                    visibleItems.firstOrNull { itemInfo ->
                        itemInfo.offset.y >= viewportTop
                    }?.index ?: visibleItems.firstOrNull()?.index ?: -1
                }
            }
                .distinctUntilChanged()
                .collect { index ->
                    val successState = state.listState as? dev.dimension.flare.common.PagingState.Success
                    if (successState == null) {
                        Log.d(TAG, "LVP Save: listState not Success yet, skipping save")
                        return@collect
                    }

                    // Mark that the feed has loaded
                    if (!feedInitiallyLoaded && index >= 0) {
                        feedInitiallyLoaded = true
                        Log.d(TAG, "LVP Save: Feed initially loaded at index=$index, deferring save to avoid overwriting LVP")
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
                            Log.d(TAG, "LVP Save: User scrolled to topmost fully visible post ID: $statusKey (index=$index)")
                            if (statusKey != null) {
                                // Extract sortId directly from the post's createdAt timestamp
                                val sortId = when (val content = item.content) {
                                    is dev.dimension.flare.ui.model.UiTimeline.ItemContent.Status ->
                                        content.createdAt.value.toEpochMilliseconds()
                                    else -> null
                                }
                                Log.d(TAG, "LVP Save: Retrieved sortId=$sortId (from post createdAt)")
                                if (sortId != null && sortId > 0) {
                                    scrollPositionRepo.saveScrollPosition(
                                        DbFeedScrollPosition(
                                            pagingKey = timelineTabItem.key,
                                            lastViewedStatusKey = statusKey,
                                            lastViewedSortId = sortId,
                                            lastUpdated = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                                        ),
                                    )
                                    lastSavedIndex = index
                                    Log.d(TAG, "LVP Save: SAVED topmost fully visible postId=$statusKey sortId=$sortId at index=$index")
                                } else {
                                    Log.d(TAG, "LVP Save: sortId is null or invalid, skipping save")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "LVP Save: Exception: ${e.message}", e)
                    }
                }
        }

        state.listState.onSuccess {
            LaunchedEffect(Unit) {
                snapshotFlow {
                    if (itemCount > 0) {
                        peek(0)?.itemKey
                    } else {
                        null
                    }
                }.mapNotNull { it }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect {
                        showNewToots = true
                        lastRefreshIndex = lazyListState.firstVisibleItemIndex
                    }
            }
        }
        LaunchedEffect(Unit) {
            snapshotFlow { lazyListState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .collect {
                    if (it > lastRefreshIndex && showNewToots) {
                        newPostCount =
                            if (newPostCount > 0) {
                                minOf(newPostCount, it - lastRefreshIndex)
                            } else {
                                it - lastRefreshIndex
                            }
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

            override fun onNewTootsShown() {
                showNewToots = false
            }
        }
    }
}
