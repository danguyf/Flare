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
import dev.dimension.flare.data.database.scroll.model.DbFeedScrollPosition
import dev.dimension.flare.data.model.TimelineTabItem
import dev.dimension.flare.data.repository.ScrollPositionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
        var lastObservedItemCount by remember { mutableStateOf(0) }

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
                            lastUpdated = kotlin.time.Clock.System.now()
                                .toEpochMilliseconds(),
                        ),
                    )
                } else {
                    println("[$LVP_LOG_TAG] LVP NOT saved: item at index $index is not a StatusContent. itemCount=${(currentState.listState as? TimelineSuccess)?.itemCount ?: 0}")
                }
            }
        }

        val currentSaveFunction by rememberUpdatedState(::saveCurrentScrollPositionSync)

        DisposableEffect(Unit) {
            onDispose {
                scope.launch {
                    currentSaveFunction()
                }
            }
        }

        // Separate Effect to monitor when the scroll actually reflects the target index.
        // Restoration is only marked COMPLETED once the UI layout has finished moving to the target.
        // This solves the race condition where Bug 1 auto-hide logic clears the indicator prematurely.
        LaunchedEffect(restorationState.status, restorationState.targetIndex) {
            if (restorationState.status == RestorationState.Status.RESTORING && restorationState.targetIndex >= 0) {
                println("[$LVP_LOG_TAG] Waiting for layout confirmation of scroll to index ${restorationState.targetIndex}")
                snapshotFlow {
                    lazyListState.layoutInfo.visibleItemsInfo.any { it.index == restorationState.targetIndex } ||
                    lazyListState.firstVisibleItemIndex >= restorationState.targetIndex
                }
                .filter { it }
                .first()
                println("[$LVP_LOG_TAG] Scroll confirmed. Completing restoration cycle.")
                restorationState = restorationState.copy(status = RestorationState.Status.COMPLETED, targetIndex = -1)
            }
        }

        // Unified restoration logic keyed by tab key to avoid stale state and cancellation loops
        LaunchedEffect(timelineTabItem.key) {
            println("[$LVP_LOG_TAG] Restoration monitoring started for ${timelineTabItem.key}")

            // Fix Bug 2: Wait until the tab is actually visible before starting restoration to avoid background loops.
            snapshotFlow {
                lazyListState.layoutInfo.visibleItemsInfo.isNotEmpty() || 
                (currentState.listState.let { it is dev.dimension.flare.common.PagingState.Success && it.itemCount == 0 })
            }
            .filter { it }
            .first()

            var lastObservedSuccess: TimelineSuccess? = null

            snapshotFlow {
                val s = currentState
                val ls = s.listState as? TimelineSuccess
                ls to Triple(ls?.itemCount ?: 0, s.isRefreshing, ls?.appendState)
            }.collect { (ls, info) ->
                val (itemCount, isRefreshing, appendState) = info

                // Identity-based refresh detection ensures we don't miss rapid Twitter updates.
                val isIdentityRefresh = ls != null && restorationState.status == RestorationState.Status.COMPLETED && ls !== lastObservedSuccess
                lastObservedSuccess = ls

                if (isRefreshing || isIdentityRefresh) {
                    // Manual or background refresh triggers a state reset if we were previously stable.
                    if (restorationState.status == RestorationState.Status.COMPLETED || isIdentityRefresh) {
                        println("[$LVP_LOG_TAG] Refresh detected, resetting restoration state to READY.")
                        restorationState = RestorationState(status = RestorationState.Status.READY)
                        lastObservedItemCount = 0
                    }
                    return@collect
                }

                if (restorationState.status == RestorationState.Status.COMPLETED) return@collect

                // Handle empty or error states to clear the loading spinner if no progress can be made.
                if (ls == null) {
                    val listState = currentState.listState
                    if (listState is dev.dimension.flare.common.PagingState.Error) {
                        println("[$LVP_LOG_TAG] Feed errored, completing search.")
                        restorationState = restorationState.copy(status = RestorationState.Status.COMPLETED)
                    }
                    return@collect
                }

                if (itemCount == 0) return@collect

                // If waiting for scroll confirmation in the other effect, don't restart search logic here.
                if (restorationState.targetIndex >= 0) return@collect

                val giveUpSearch = { reason: String ->
                    println("[$LVP_LOG_TAG] $reason. Stopping search for ${timelineTabItem.key}.")
                    if (restorationState.status != RestorationState.Status.READY) {
                        lvpRestoreFailedEventsSource.tryEmit(Unit)
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

                // Transition to active searching
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
                            restorationState = restorationState.copy(targetIndex = lvpIndex)
                        } else {
                            // LVP is at index 0, we are done immediately
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

        return object : State, TimelineItemPresenter.State by state {
            // NP should appear once COMPLETED if there are posts above.
            override val showNewToots = currentVisibleIndexState.value > 0 && restorationState.status == RestorationState.Status.COMPLETED
            override val lazyListState = lazyListState
            override val newPostsCount = currentVisibleIndexState.value
            override val lvpRestoreFailedEvents = lvpRestoreFailedEventsSource
            override val isRefreshing: Boolean
                get() = state.isRefreshing || (restorationState.status != RestorationState.Status.COMPLETED && restorationState.status != RestorationState.Status.READY)

            override fun refreshSync() {
                println("[$LVP_LOG_TAG] refreshSync triggered. Index=${lazyListState.firstVisibleItemIndex}")
                // Immediate reset of indicator states to avoid flickering during refresh
                restorationState = RestorationState(status = RestorationState.Status.READY)
                lastObservedItemCount = 0

                scope.launch {
                    currentSaveFunction()
                    restorationState = RestorationState(status = RestorationState.Status.READY)
                    newPostsState = NewPostsState()
                    lastObservedIndicatorItemCount = 0
                    state.refreshSync()
                }
            }

            override suspend fun refreshSuspend() {
                println("[$LVP_LOG_TAG] refreshSuspend triggered. Index=${lazyListState.firstVisibleItemIndex}")
                // Immediate reset of indicator states to avoid flickering during refresh
                restorationState = RestorationState(status = RestorationState.Status.READY)
                lastObservedItemCount = 0

                scope.launch {
                    currentSaveFunction()
                    restorationState = RestorationState(status = RestorationState.Status.READY)
                    newPostsState = NewPostsState()
                    lastObservedIndicatorItemCount = 0
                    state.refreshSuspend()
                }
            }
        }
    }
}
