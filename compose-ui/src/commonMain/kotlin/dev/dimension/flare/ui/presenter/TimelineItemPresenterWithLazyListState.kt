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
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.koinInject

private const val LVP_LOG_TAG = "LVP_REFRESH"
private const val LVP_RESTORE_LIMIT = 500

private typealias TimelineSuccess = dev.dimension.flare.common.PagingState.Success<dev.dimension.flare.ui.model.UiTimeline>
private typealias StatusContent = dev.dimension.flare.ui.model.UiTimeline.ItemContent.Status

private data class RestorationState(
    val status: Status = Status.READY,
    val lastObservedItemCount: Int = 0,
    val targetIndex: Int = -1,
) {
    enum class Status {
        READY,      // Initial state or waiting for data to begin restoration
        RESTORING,  // Actively searching or waiting for a scroll to be reflected in layout
        NOT_FOUND,  // Searched current items, waiting for more data to be appended
        COMPLETED,  // Restoration finished (success, failed, or gave up)
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

    /**
     * Attempts to find the saved LVP in the currently loaded posts and scrolls to it.
     * Returns the index if found, or -1 if not found.
     */
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
            println("[$LVP_LOG_TAG] LVP found at index=$foundIndex for ${timelineTabItem.key}, scrolling to it")
            lazyListState.scrollToItem(foundIndex, scrollOffset = 0)
            foundIndex
        } else {
            println("[$LVP_LOG_TAG] LVP NOT found in $itemCount loaded posts, scrolling to bottom to trigger older post loading")
            if (itemCount > 0) {
                // Scroll to the very last item to trigger Paging's append action
                lazyListState.scrollToItem(itemCount - 1)
            }
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
                val firstIndex = lazyListState.firstVisibleItemIndex
                val topItem = lazyListState.layoutInfo.visibleItemsInfo.find { it.index == firstIndex }
                if (topItem != null) {
                    if (topItem.offset.y <= -(topItem.size.height / 2)) {
                        topItem.index + 1
                    } else {
                        topItem.index
                    }
                } else {
                    firstIndex
                }
            }
        }

        val currentState by rememberUpdatedState(state)

        /**
         * Captures the status at the current first visible index and saves it to the database.
         */
        suspend fun saveCurrentScrollPositionSync() {
            val index = lazyListState.firstVisibleItemIndex
            if (index >= 0) {
                // Requirement: Whenever a refresh is triggered the current LVP must be saved.
                val item = (currentState.listState as? TimelineSuccess)?.peek(index)
                val status = item?.content as? StatusContent
                if (status != null) {
                    println("[$LVP_LOG_TAG] Saving LVP for ${timelineTabItem.key}: ${status.statusKey} at index $index")
                    scrollPositionRepo.saveScrollPosition(
                        DbFeedScrollPosition(
                            pagingKey = timelineTabItem.key,
                            lastViewedStatusKey = status.statusKey,
                            lastViewedSortId = status.createdAt.value.toEpochMilliseconds(),
                            lastUpdated = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                        ),
                    )
                } else {
                    println("[$LVP_LOG_TAG] LVP NOT saved: item at index $index is not a StatusContent. itemCount=${(currentState.listState as? TimelineSuccess)?.itemCount ?: 0}")
                }
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                scope.launch {
                    saveCurrentScrollPositionSync()
                }
            }
        }

        // Separate Effect to monitor when the scroll actually reflects the target index.
        // Restoration is only marked COMPLETED once the UI layout has finished moving to the target.
        // This solves the race condition where auto-hide logic clears the indicator prematurely.
        LaunchedEffect(restorationState.status, restorationState.targetIndex) {
            if (restorationState.status == RestorationState.Status.RESTORING && restorationState.targetIndex >= 0) {
                println("[$LVP_LOG_TAG] Waiting for layout confirmation of scroll to index ${restorationState.targetIndex}")
                withTimeoutOrNull(5000) {
                    snapshotFlow { 
                        lazyListState.layoutInfo.visibleItemsInfo.any { it.index == restorationState.targetIndex } ||
                        lazyListState.firstVisibleItemIndex >= restorationState.targetIndex
                    }
                    .filter { it }
                    .first()
                }
                println("[$LVP_LOG_TAG] Scroll confirmed. Completing restoration cycle.")
                restorationState = restorationState.copy(status = RestorationState.Status.COMPLETED, targetIndex = -1)
            }
        }

        // Unified restoration logic keyed by tab key to avoid stale state and cancellation loops
        LaunchedEffect(timelineTabItem.key) {
            println("[$LVP_LOG_TAG] Restoration monitoring started for ${timelineTabItem.key}")

            // Guard: Wait until the tab is visible before starting restoration to avoid background loops.
            snapshotFlow { 
                lazyListState.layoutInfo.visibleItemsInfo.isNotEmpty() || 
                (currentState.listState.let { it is dev.dimension.flare.common.PagingState.Success && it.itemCount == 0 })
            }
            .filter { it }
            .first()

            snapshotFlow {
                val s = currentState
                val ls = s.listState as? TimelineSuccess
                ls to Triple(ls?.itemCount ?: 0, s.isRefreshing, ls?.appendState)
            }.collect { (ls, info) ->
                val (itemCount, isRefreshing, appendState) = info

                if (isRefreshing) {
                    // Only reset to READY if we were previously COMPLETED.
                    // This allows the initial load spinner to transition seamlessly into the restoration search.
                    if (restorationState.status == RestorationState.Status.COMPLETED) {
                        println("[$LVP_LOG_TAG] Refresh detected, resetting restoration state to READY.")
                        restorationState = RestorationState(status = RestorationState.Status.READY)
                        newPostsState = NewPostsState()
                        lastObservedIndicatorItemCount = 0
                    }
                    return@collect
                }

                if (restorationState.status == RestorationState.Status.COMPLETED) return@collect

                // Handle empty or error states to clear the loading spinner if no progress can be made.
                if (itemCount == 0 && ls == null) {
                    val listState = currentState.listState
                    if (listState is dev.dimension.flare.common.PagingState.Error || listState is dev.dimension.flare.common.PagingState.Empty) {
                        println("[$LVP_LOG_TAG] Feed ended or errored, completing search.")
                        restorationState = restorationState.copy(status = RestorationState.Status.COMPLETED)
                    }
                    return@collect
                }

                if (ls == null || itemCount == 0) return@collect

                // If waiting for scroll confirmation in the other effect, don't restart search logic here.
                if (restorationState.targetIndex >= 0) return@collect

                val giveUpSearch = { reason: String ->
                    println("[$LVP_LOG_TAG] $reason. Stopping search for ${timelineTabItem.key}.")
                    if (restorationState.status != RestorationState.Status.READY) {
                        lvpRestoreFailedEventsSource.tryEmit(Unit)
                    }
                    // Trigger New Posts indicator for whatever is currently above us
                    val visIndex = lazyListState.firstVisibleItemIndex
                    if (visIndex > 0) {
                        newPostsState = newPostsState.copy(showIndicator = true, count = visIndex)
                    }
                    restorationState = restorationState.copy(status = RestorationState.Status.COMPLETED)
                }

                // Protect against endless searching loops by enforcing the strictly specified limit.
                if (itemCount > LVP_RESTORE_LIMIT && restorationState.status != RestorationState.Status.READY) {
                    giveUpSearch("LVP not found within limit of $LVP_RESTORE_LIMIT posts")
                    return@collect
                }

                // Re-scan throttle: only perform search if itemCount grew or if we haven't started yet.
                if (restorationState.status == RestorationState.Status.NOT_FOUND && itemCount <= restorationState.lastObservedItemCount) {
                    if (appendState is LoadState.NotLoading && appendState.endOfPaginationReached) {
                        giveUpSearch("Feed exhausted, LVP not found")
                    }
                    return@collect
                }

                // Internal status remains RESTORING until found OR target-scrolled
                if (restorationState.status != RestorationState.Status.RESTORING) {
                    restorationState = restorationState.copy(status = RestorationState.Status.RESTORING)
                }

                try {
                    val scrollPosition = scrollPositionRepo.getScrollPosition(timelineTabItem.key)
                    if (scrollPosition == null) {
                        println("[$LVP_LOG_TAG] No saved LVP found in DB for ${timelineTabItem.key}")
                        restorationState = restorationState.copy(status = RestorationState.Status.COMPLETED)
                        return@collect
                    }

                    val lvpIndex = restoreLvpInFeed(lazyListState, ls, scrollPosition)
                    if (lvpIndex >= 0) {
                        if (lvpIndex > 0) {
                            // Indicator count is exactly the number of raw items above the LVP.
                            newPostsState = newPostsState.copy(showIndicator = true, count = lvpIndex)
                            // Transition to target-tracking phase
                            restorationState = restorationState.copy(targetIndex = lvpIndex)
                        } else {
                            // LVP is at the top, done immediately
                            restorationState = restorationState.copy(status = RestorationState.Status.COMPLETED)
                        }
                    } else {
                        // Not found in current items, wait for more pages to load via Paging append.
                        restorationState = restorationState.copy(status = RestorationState.Status.NOT_FOUND, lastObservedItemCount = itemCount)
                        if (appendState is LoadState.NotLoading && appendState.endOfPaginationReached) {
                            giveUpSearch("Feed exhausted, LVP not found")
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    println("[$LVP_LOG_TAG] Error during LVP restoration for ${timelineTabItem.key}: ${e.message}")
                    restorationState = restorationState.copy(status = RestorationState.Status.COMPLETED)
                }
            }
        }

        var lastPrependState by remember { mutableStateOf<LoadState>(LoadState.NotLoading(endOfPaginationReached = false)) }

        // Detect when new posts have been loaded at the top during a refresh
        state.listState.onSuccess {
            val success = this
            LaunchedEffect(success) {
                snapshotFlow { Triple(success.prependState, success.appendState, success.itemCount) }
                    .distinctUntilChanged()
                    .collect { (currentPrependState, currentAppendState, currentItemCount) ->
                        val visibleIndex = currentVisibleIndexState.value
                        if (lastObservedIndicatorItemCount == 0) {
                            if (currentItemCount > 0) lastObservedIndicatorItemCount = currentItemCount
                        } else if (lastPrependState is LoadState.Loading &&
                            currentPrependState is LoadState.NotLoading &&
                            currentItemCount > lastObservedIndicatorItemCount &&
                            visibleIndex > 0 &&
                            restorationState.status == RestorationState.Status.COMPLETED
                        ) {
                            val newItemsCount = currentItemCount - lastObservedIndicatorItemCount
                            val totalNewAbove = newPostsState.count + newItemsCount
                            if (newItemsCount > 0) {
                                newPostsState = newPostsState.copy(showIndicator = true, count = totalNewAbove)
                            }
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

        // Auto-hide indicator when user returns to top
        LaunchedEffect(currentVisibleIndexState.value, newPostsState.count, newPostsState.showIndicator, restorationState.status) {
            val currentVisibleIndex = currentVisibleIndexState.value
            if (restorationState.status == RestorationState.Status.COMPLETED) {
                if (newPostsState.showIndicator) {
                    // Hide if count reaches zero OR if at absolute top
                    if (newPostsState.count <= 0 || (currentVisibleIndex == 0 && lazyListState.firstVisibleItemScrollOffset == 0)) {
                        newPostsState = newPostsState.copy(showIndicator = false, count = 0)
                    }
                } else if (currentVisibleIndex == 0) {
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
                get() = state.isRefreshing || (restorationState.status != RestorationState.Status.COMPLETED && restorationState.status != RestorationState.Status.READY)

            override fun onNewTootsShown() {
                newPostsState = newPostsState.copy(showIndicator = false)
            }

            override fun refreshSync() {
                println("[$LVP_LOG_TAG] refreshSync triggered. Index=${lazyListState.firstVisibleItemIndex}")
                scope.launch {
                    saveCurrentScrollPositionSync()
                    restorationState = RestorationState(status = RestorationState.Status.READY)
                    newPostsState = NewPostsState()
                    lastObservedIndicatorItemCount = 0
                    state.refreshSync()
                }
            }

            override suspend fun refreshSuspend() {
                println("[$LVP_LOG_TAG] refreshSuspend triggered. Index=${lazyListState.firstVisibleItemIndex}")
                saveCurrentScrollPositionSync()
                restorationState = RestorationState(status = RestorationState.Status.READY)
                newPostsState = NewPostsState()
                lastObservedIndicatorItemCount = 0
                state.refreshSuspend()
            }
        }
    }
}
