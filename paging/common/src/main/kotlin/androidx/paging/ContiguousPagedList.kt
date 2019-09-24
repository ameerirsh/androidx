/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.paging

import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.annotation.RestrictTo
import androidx.paging.LoadType.END
import androidx.paging.LoadType.REFRESH
import androidx.paging.LoadType.START
import androidx.paging.LoadState.Idle
import androidx.paging.LoadState.Loading
import androidx.paging.PagedSource.KeyProvider
import androidx.paging.PagedSource.LoadResult.Page.Companion.COUNT_UNDEFINED
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
open class ContiguousPagedList<K : Any, V : Any>(
    pagedSource: PagedSource<K, V>,
    internal val coroutineScope: CoroutineScope,
    internal val notifyDispatcher: CoroutineDispatcher,
    internal val backgroundDispatcher: CoroutineDispatcher,
    internal val boundaryCallback: BoundaryCallback<V>?,
    config: Config,
    initialPage: PagedSource.LoadResult.Page<K, V>,
    lastLoad: Int
) : PagedList<V>(
    pagedSource,
    PagedStorage<V>(),
    config
), PagedStorage.Callback, Pager.PageConsumer<V> {
    internal companion object {
        internal const val LAST_LOAD_UNSPECIFIED = -1

        internal fun getPrependItemsRequested(
            prefetchDistance: Int,
            index: Int,
            leadingNulls: Int
        ) = prefetchDistance - (index - leadingNulls)

        internal fun getAppendItemsRequested(
            prefetchDistance: Int,
            index: Int,
            itemsBeforeTrailingNulls: Int
        ) = index + prefetchDistance + 1 - itemsBeforeTrailingNulls
    }

    private var prependItemsRequested = 0
    private var appendItemsRequested = 0

    // if set to true, boundaryCallback is non-null, and should
    // be dispatched when nearby load has occurred
    private var boundaryCallbackBeginDeferred = false
    private var boundaryCallbackEndDeferred = false

    // lowest and highest index accessed by loadAround. Used to
    // decide when boundaryCallback should be dispatched
    private var lowestIndexAccessed = Int.MAX_VALUE
    private var highestIndexAccessed = Int.MIN_VALUE

    private var replacePagesWithNulls = false

    private val shouldTrim = (pagedSource.keyProvider is KeyProvider.Positional ||
            pagedSource.keyProvider is KeyProvider.ItemKey) &&
            config.maxSize != Config.MAX_SIZE_UNBOUNDED

    private val pager = Pager(
        coroutineScope,
        config,
        pagedSource,
        notifyDispatcher,
        backgroundDispatcher,
        this,
        initialPage,
        storage
    )

    override val isDetached
        get() = pager.isDetached

    override val lastKey
        get() = when (val keyProvider = pagedSource.keyProvider) {
            is KeyProvider.Positional -> {
                @Suppress("UNCHECKED_CAST")
                lastLoad as K
            }
            is KeyProvider.PageKey ->
                throw IllegalStateException("Cannot get key by item from KeyProvider.PageKey")
            is KeyProvider.ItemKey -> lastItem?.let { keyProvider.getKey(it) }
        }

    /**
     * Given a page result, apply or drop it, and return whether more loading is needed.
     */
    override fun onPageResult(
        type: LoadType,
        page: PagedSource.LoadResult.Page<*, V>
    ): Boolean {
        var continueLoading = false
        val list = page.data

        // if we end up trimming, we trim from side that's furthest from most recent access
        val trimFromFront = lastLoad > storage.middleOfLoadedRange

        // is the new page big enough to warrant pre-trimming (i.e. dropping) it?
        val skipNewPage = shouldTrim && storage.shouldPreTrimNewPage(
            config.maxSize,
            requiredRemainder,
            list.size
        )

        if (type == END) {
            if (skipNewPage && !trimFromFront) {
                // don't append this data, drop it
                appendItemsRequested = 0
            } else {
                storage.appendPage(list, this@ContiguousPagedList)
                appendItemsRequested -= list.size
                if (appendItemsRequested > 0 && list.isNotEmpty()) {
                    continueLoading = true
                }
            }
        } else if (type == START) {
            if (skipNewPage && trimFromFront) {
                // don't append this data, drop it
                prependItemsRequested = 0
            } else {
                storage.prependPage(list, this@ContiguousPagedList)
                prependItemsRequested -= list.size
                if (prependItemsRequested > 0 && list.isNotEmpty()) {
                    continueLoading = true
                }
            }
        } else {
            throw IllegalArgumentException("unexpected result type $type")
        }

        if (shouldTrim) {
            // Try and trim, but only if the side being trimmed isn't actually fetching.
            // For simplicity (both of impl here, and contract w/ PagedSource) we don't
            // allow fetches in same direction - this means reading the load state is safe.
            if (trimFromFront) {
                if (pager.loadStateManager.startState !is Loading) {
                    if (storage.trimFromFront(
                            replacePagesWithNulls,
                            config.maxSize,
                            requiredRemainder,
                            this@ContiguousPagedList
                        )
                    ) {
                        // trimmed from front, ensure we can fetch in that dir
                        pager.loadStateManager.setState(START, Idle)
                    }
                }
            } else {
                if (pager.loadStateManager.endState !is Loading) {
                    if (storage.trimFromEnd(
                            replacePagesWithNulls,
                            config.maxSize,
                            requiredRemainder,
                            this@ContiguousPagedList
                        )
                    ) {
                        pager.loadStateManager.setState(END, Idle)
                    }
                }
            }
        }

        triggerBoundaryCallback(type, list)
        return continueLoading
    }

    override fun onStateChanged(type: LoadType, state: LoadState) =
        dispatchStateChange(type, state)

    private fun triggerBoundaryCallback(type: LoadType, page: List<V>) {
        if (boundaryCallback != null) {
            val deferEmpty = storage.size == 0
            val deferBegin = (!deferEmpty && type == START && page.isEmpty())
            val deferEnd = (!deferEmpty && type == END && page.isEmpty())
            deferBoundaryCallbacks(deferEmpty, deferBegin, deferEnd)
        }
    }

    // Creation thread for initial synchronous load, otherwise main thread
    // Safe to access main thread only state - no other thread has reference during construction
    @AnyThread
    internal fun deferBoundaryCallbacks(
        deferEmpty: Boolean,
        deferBegin: Boolean,
        deferEnd: Boolean
    ) {
        if (boundaryCallback == null) {
            throw IllegalStateException("Can't defer BoundaryCallback, no instance")
        }

        /*
         * If lowest/highest haven't been initialized, set them to storage size,
         * since placeholders must already be computed by this point.
         *
         * This is just a minor optimization so that BoundaryCallback callbacks are sent immediately
         * if the initial load size is smaller than the prefetch window (see
         * TiledPagedListTest#boundaryCallback_immediate())
         */
        if (lowestIndexAccessed == Int.MAX_VALUE) {
            lowestIndexAccessed = storage.size
        }
        if (highestIndexAccessed == Int.MIN_VALUE) {
            highestIndexAccessed = 0
        }

        if (deferEmpty || deferBegin || deferEnd) {
            // Post to the main thread, since we may be on creation thread currently
            coroutineScope.launch(notifyDispatcher) {
                // on is dispatched immediately, since items won't be accessed

                if (deferEmpty) {
                    boundaryCallback.onZeroItemsLoaded()
                }

                // for other callbacks, mark deferred, and only dispatch if loadAround
                // has been called near to the position
                if (deferBegin) {
                    boundaryCallbackBeginDeferred = true
                }
                if (deferEnd) {
                    boundaryCallbackEndDeferred = true
                }
                tryDispatchBoundaryCallbacks(false)
            }
        }
    }

    /**
     * Call this when lowest/HighestIndexAccessed are changed, or boundaryCallbackBegin/EndDeferred
     * is set.
     */
    private fun tryDispatchBoundaryCallbacks(post: Boolean) {
        val dispatchBegin = boundaryCallbackBeginDeferred &&
                lowestIndexAccessed <= config.prefetchDistance
        val dispatchEnd = boundaryCallbackEndDeferred &&
                highestIndexAccessed >= size - 1 - config.prefetchDistance

        if (!dispatchBegin && !dispatchEnd) return

        if (dispatchBegin) {
            boundaryCallbackBeginDeferred = false
        }
        if (dispatchEnd) {
            boundaryCallbackEndDeferred = false
        }
        if (post) {
            coroutineScope.launch(notifyDispatcher) {
                dispatchBoundaryCallbacks(dispatchBegin, dispatchEnd)
            }
        } else {
            dispatchBoundaryCallbacks(dispatchBegin, dispatchEnd)
        }
    }

    private fun dispatchBoundaryCallbacks(begin: Boolean, end: Boolean) {
        // safe to deref boundaryCallback here, since we only defer if boundaryCallback present
        if (begin) {
            boundaryCallback!!.onItemAtFrontLoaded(storage.firstLoadedItem)
        }
        if (end) {
            boundaryCallback!!.onItemAtEndLoaded(storage.lastLoadedItem)
        }
    }

    override fun retry() {
        super.retry()
        pager.retry()

        pager.loadStateManager.refreshState.run {
            // If loading the next PagedList failed, signal the retry callback.
            if (this is LoadState.Error) {
                refreshRetryCallback?.run()
            }
        }
    }

    init {
        this.lastLoad = lastLoad
        if (config.enablePlaceholders) {
            // Placeholders enabled, pass raw data to storage init
            storage.init(
                if (initialPage.itemsBefore != COUNT_UNDEFINED) initialPage.itemsBefore else 0,
                initialPage.data,
                if (initialPage.itemsAfter != COUNT_UNDEFINED) initialPage.itemsAfter else 0,
                0,
                this
            )
        } else {
            // If placeholder are disabled, avoid passing leading/trailing nulls, since PagedSource
            // may have passed them anyway.
            storage.init(
                0,
                initialPage.data,
                0,
                if (initialPage.itemsBefore != COUNT_UNDEFINED) initialPage.itemsBefore else 0,
                this
            )
        }

        if (this.lastLoad == LAST_LOAD_UNSPECIFIED) {
            // Because the ContiguousPagedList wasn't initialized with a last load position,
            // initialize it to the middle of the initial load
            val itemsBefore = if (initialPage.itemsBefore != COUNT_UNDEFINED) {
                initialPage.itemsBefore
            } else {
                // Undefined, so map to zero
                0
            }
            this.lastLoad = itemsBefore + initialPage.data.size / 2
        }
        triggerBoundaryCallback(REFRESH, initialPage.data)
    }

    override fun dispatchCurrentLoadState(callback: LoadStateListener) {
        pager.loadStateManager.dispatchCurrentLoadState(callback)
    }

    override fun setInitialLoadState(loadType: LoadType, loadState: LoadState) {
        pager.loadStateManager.setState(loadType, loadState)
    }

    @MainThread
    override fun loadAroundInternal(index: Int) {
        lastLoad = index + positionOffset

        val prependItems =
            getPrependItemsRequested(config.prefetchDistance, index, storage.leadingNullCount)
        val appendItems = getAppendItemsRequested(
            config.prefetchDistance,
            index,
            storage.leadingNullCount + storage.storageCount
        )

        prependItemsRequested = maxOf(prependItems, prependItemsRequested)
        if (prependItemsRequested > 0) {
            pager.trySchedulePrepend()
        }

        appendItemsRequested = maxOf(appendItems, appendItemsRequested)
        if (appendItemsRequested > 0) {
            pager.tryScheduleAppend()
        }

        lowestIndexAccessed = minOf(lowestIndexAccessed, index)
        highestIndexAccessed = maxOf(highestIndexAccessed, index)

        /*
         * lowestIndexAccessed / highestIndexAccessed have been updated, so check if we need to
         * dispatch boundary callbacks. Boundary callbacks are deferred until last items are loaded,
         * and accesses happen near the boundaries.
         *
         * Note: we post here, since RecyclerView may want to add items in response, and this
         * call occurs in PagedListAdapter bind.
         */
        tryDispatchBoundaryCallbacks(true)
    }

    override fun detach() = pager.detach()

    @MainThread
    override fun onInitialized(count: Int) {
        notifyInserted(0, count)
        // Simple heuristic to decide if, when dropping pages, we should replace with placeholders.
        // If we're not presenting placeholders at initialization time, we won't add them when
        // we drop a page. Note that we don't use config.enablePlaceholders, since the
        // PagedSource may have opted not to load any.
        replacePagesWithNulls = storage.leadingNullCount > 0 || storage.trailingNullCount > 0
    }

    @MainThread
    override fun onPagePrepended(leadingNulls: Int, changed: Int, added: Int) {
        // finally dispatch callbacks, after prepend may have already been scheduled
        notifyChanged(leadingNulls, changed)
        notifyInserted(0, added)

        // update last loadAround index
        lastLoad += added

        // update access range
        lowestIndexAccessed += added
        highestIndexAccessed += added
    }

    @MainThread
    override fun onPageAppended(endPosition: Int, changed: Int, added: Int) {
        // finally dispatch callbacks, after append may have already been scheduled
        notifyChanged(endPosition, changed)
        notifyInserted(endPosition + changed, added)
    }

    override fun onPagesRemoved(startOfDrops: Int, count: Int) = notifyRemoved(startOfDrops, count)

    override fun onPagesSwappedToPlaceholder(startOfDrops: Int, count: Int) =
        notifyChanged(startOfDrops, count)
}
