package com.lagradost.cloudstream3.syncproviders.providers

import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.library.ListSorting
import com.lagradost.cloudstream3.utils.Coroutines.ioWork
import com.lagradost.cloudstream3.utils.DataStoreHelper.getAllFavorites
import com.lagradost.cloudstream3.utils.DataStoreHelper.getAllWatchStateIds
import com.lagradost.cloudstream3.utils.DataStoreHelper.getBookmarkedData
import com.lagradost.cloudstream3.utils.DataStoreHelper.getResultWatchState
import com.lagradost.cloudstream3.utils.txt

class LocalList : SyncAPI() {
    override val name = "Local"
    override val idPrefix = "local"

    override val icon: Int = R.drawable.ic_baseline_storage_24
    override val requiresLogin = false
    override val createAccountUrl = null
    override var requireLibraryRefresh = true
    override val syncIdName = SyncIdName.LocalList

    override suspend fun library(auth: AuthData?): SyncAPI.LibraryMetadata? {
        val watchStatusIds = ioWork {
            getAllWatchStateIds()?.map { id ->
                Pair(id, getResultWatchState(id))
            }
        }?.distinctBy { it.first } ?: emptyList()

        val list = ioWork {
            // 1. Favorites
            val favoritesItems = getAllFavorites().mapNotNull {
                it.toLibraryItem()
            }

            // 2. Watchlist (Plan to Watch, Watching, On Hold)
            val watchlistItems = watchStatusIds.filter {
                it.second == WatchType.PLANTOWATCH || it.second == WatchType.WATCHING || it.second == WatchType.ONHOLD
            }.mapNotNull {
                getBookmarkedData(it.first)?.toLibraryItem(it.first.toString())
            }

            // 3. History (Completed & Resume Watching)
            val completedItems = watchStatusIds.filter {
                it.second == WatchType.COMPLETED
            }.mapNotNull {
                getBookmarkedData(it.first)?.toLibraryItem(it.first.toString())
            }

            val resumeWatching = HomeViewModel.getResumeWatching() ?: emptyList()
            val resumeItems: List<SyncAPI.LibraryItem> = resumeWatching.map {
                SyncAPI.LibraryItem(
                    name = it.name,
                    url = it.url,
                    syncId = (it.parentId ?: it.id ?: 0).toString(),
                    episodesCompleted = it.episode,
                    episodesTotal = null,
                    personalRating = null,
                    lastUpdatedUnixTime = System.currentTimeMillis(),
                    apiName = it.apiName,
                    type = it.type,
                    posterUrl = it.posterUrl,
                    posterHeaders = it.posterHeaders,
                    quality = it.quality,
                    releaseDate = null,
                    id = it.id,
                    plot = null,
                    score = it.score,
                    tags = null
                )
            }

            val historyItems = (completedItems + resumeItems).distinctBy { it.url }

            listOf(
                SyncAPI.LibraryList(txt(R.string.favorites_list_name), favoritesItems),
                SyncAPI.LibraryList(txt(R.string.watchlist_tab), watchlistItems),
                SyncAPI.LibraryList(txt(R.string.history), historyItems)
            )
        }

        return SyncAPI.LibraryMetadata(
            list,
            setOf(
                ListSorting.UpdatedNew,
                ListSorting.ReleaseDateNew,
                ListSorting.AlphabeticalA,
            )
        )
    }
}
