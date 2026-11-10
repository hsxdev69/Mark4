package com.lagradost.cloudstream3.ui.quicksearch

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.appcompat.widget.SearchView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.databinding.QuickSearchBinding
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.home.HomeFragment
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.filterSearchResultByFilmQuality
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.getSpanCount
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.UIHelper.popCurrentPage

enum class CategoryType {
    MOVIES,
    TV_SHOWS,
    ANIME
}

class QuickSearchFragment : BaseFragment<QuickSearchBinding>(
    BaseFragment.BindingCreator.Inflate(QuickSearchBinding::inflate)
) {
    companion object {
        const val AUTOSEARCH_KEY = "autosearch"
        const val PROVIDER_KEY = "providers"

        fun pushSearch(
            autoSearch: String? = null,
            providers: Array<String>? = null
        ) {
            pushSearch(activity, autoSearch, providers)
        }

        fun pushSearch(
            activity: Activity?,
            autoSearch: String? = null,
            providers: Array<String>? = null
        ) {
            activity.navigate(R.id.global_to_navigation_quick_search, Bundle().apply {
                providers?.let {
                    putStringArray(PROVIDER_KEY, it)
                }
                autoSearch?.let {
                    putString(
                        AUTOSEARCH_KEY,
                        it.trim()
                            .removeSuffix("(DUB)")
                            .removeSuffix("(SUB)")
                            .removeSuffix("(Dub)")
                            .removeSuffix("(Sub)").trim()
                    )
                }
            })
        }

        var clickCallback: ((SearchClickCallback) -> Unit)? = null
    }

    private var currentCategory = CategoryType.MOVIES
    private var currentPage = 1
    private var isLoading = false
    private var hasMorePages = true
    private val allCategoryItems = mutableListOf<SearchResponse>()
    private var searchAdapter: SearchAdapter? = null

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(view)

        // Fix grid
        HomeFragment.currentSpan = view.context.getSpanCount()
        binding?.quickSearchAutofitResults?.spanCount = HomeFragment.currentSpan
        HomeFragment.configEvent.invoke()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        activity?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        )
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onDestroy() {
        super.onDestroy()
        clickCallback = null
    }

    private fun loadCategoryContent(category: CategoryType, page: Int) {
        if (isLoading) return
        isLoading = true

        activity?.runOnUiThread {
            binding?.quickSearchLoadingBar?.isVisible = true
        }

        ioSafe {
            try {
                val api = APIHolder.getApiFromNameNull(DataStoreHelper.currentHomePage)
                    ?: APIHolder.apis.firstOrNull { it.name == "AniVortex" }
                    ?: APIHolder.apis.firstOrNull()

                val newItems = mutableListOf<SearchResponse>()
                var hasNextPage = false

                if (api != null) {
                    val matchingSections = when (category) {
                        CategoryType.MOVIES -> api.mainPage.filter {
                            it.name.contains("Movie", ignoreCase = true) || it.name.equals("Popular", ignoreCase = true)
                        }
                        CategoryType.TV_SHOWS -> api.mainPage.filter {
                            it.name.contains("Series", ignoreCase = true) || it.name.contains("TV", ignoreCase = true) || it.name.contains("Show", ignoreCase = true) || it.name.contains("Netflix", ignoreCase = true) || it.name.contains("Prime", ignoreCase = true) || it.name.contains("Hotstar", ignoreCase = true)
                        }
                        CategoryType.ANIME -> api.mainPage.filter {
                            it.name.contains("Anime", ignoreCase = true)
                        }
                    }

                    if (matchingSections.isNotEmpty()) {
                        for (section in matchingSections.take(3)) {
                            try {
                                val response = api.getMainPage(
                                    page,
                                    MainPageRequest(section.name, section.data, section.horizontalImages)
                                )
                                response?.items?.let { homeLists ->
                                    homeLists.forEach { homeList ->
                                        newItems.addAll(homeList.list)
                                    }
                                }
                                if (response?.hasNext == true) {
                                    hasNextPage = true
                                }
                            } catch (e: Throwable) {
                                logError(e)
                            }
                        }
                    }

                    // Fallback to paginated search if mainPage sections are empty
                    if (newItems.isEmpty() && page <= 5) {
                        val query = when (category) {
                            CategoryType.MOVIES -> "movie"
                            CategoryType.TV_SHOWS -> "series"
                            CategoryType.ANIME -> "anime"
                        }
                        try {
                            val searchResult = api.search(query, page) ?: api.search(query)
                            when (searchResult) {
                                is SearchResponseList -> newItems.addAll(searchResult.items)
                                is List<*> -> newItems.addAll(searchResult.filterIsInstance<SearchResponse>())
                            }
                            hasNextPage = newItems.isNotEmpty()
                        } catch (e: Throwable) {
                            logError(e)
                        }
                    }
                }

                // Filter by category type
                val filteredItems = when (category) {
                    CategoryType.MOVIES -> newItems.filter {
                        it.type == null || it.type == TvType.Movie || it.type == TvType.AnimeMovie || it is MovieSearchResponse
                    }
                    CategoryType.TV_SHOWS -> newItems.filter {
                        it.type == null || it.type == TvType.TvSeries || it is TvSeriesSearchResponse
                    }
                    CategoryType.ANIME -> newItems.filter {
                        it.type == null || it.type == TvType.Anime || it.type == TvType.AnimeMovie || it.type == TvType.OVA
                    }
                }.distinctBy { it.url }

                val itemsToAdd = if (filteredItems.isNotEmpty()) filteredItems else newItems.distinctBy { it.url }

                activity?.runOnUiThread {
                    if (page == 1) {
                        allCategoryItems.clear()
                    }
                    allCategoryItems.addAll(itemsToAdd)
                    val qualityFiltered = context?.filterSearchResultByFilmQuality(allCategoryItems) ?: allCategoryItems
                    searchAdapter?.submitList(qualityFiltered.toList())
                    searchAdapter?.hasNext = hasNextPage || itemsToAdd.isNotEmpty()
                    hasMorePages = hasNextPage || itemsToAdd.isNotEmpty()
                }
            } catch (e: Throwable) {
                logError(e)
            } finally {
                isLoading = false
                activity?.runOnUiThread {
                    binding?.quickSearchLoadingBar?.isVisible = false
                }
            }
        }
    }

    override fun onBindingCreated(binding: QuickSearchBinding) {
        binding.quickSearchMasterRecycler.isGone = true
        binding.quickSearchAutofitResults.isVisible = true

        searchAdapter = SearchAdapter(binding.quickSearchAutofitResults) { callback ->
            SearchHelper.handleSearchClickCallback(callback)
        }

        binding.quickSearchAutofitResults.apply {
            setRecycledViewPool(SearchAdapter.sharedPool)
            adapter = searchAdapter
            spanCount = context?.getSpanCount() ?: 3

            // Infinite scrolling
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    if (dy > 0 && !isLoading && hasMorePages) {
                        val layoutManager = recyclerView.layoutManager as? GridLayoutManager
                        val totalItemCount = layoutManager?.itemCount ?: 0
                        val lastVisibleItem = layoutManager?.findLastVisibleItemPosition() ?: 0
                        if (totalItemCount > 0 && lastVisibleItem >= totalItemCount - 6) {
                            currentPage++
                            loadCategoryContent(currentCategory, currentPage)
                        }
                    }
                }
            })
        }

        // Setup 3 Category Chips
        binding.chipCategoryMovies.setOnClickListener {
            if (currentCategory != CategoryType.MOVIES) {
                currentCategory = CategoryType.MOVIES
                currentPage = 1
                loadCategoryContent(currentCategory, 1)
            }
        }

        binding.chipCategoryTv.setOnClickListener {
            if (currentCategory != CategoryType.TV_SHOWS) {
                currentCategory = CategoryType.TV_SHOWS
                currentPage = 1
                loadCategoryContent(currentCategory, 1)
            }
        }

        binding.chipCategoryAnime.setOnClickListener {
            if (currentCategory != CategoryType.ANIME) {
                currentCategory = CategoryType.ANIME
                currentPage = 1
                loadCategoryContent(currentCategory, 1)
            }
        }

        // SearchView filter support
        binding.quickSearch.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                filterCurrentItems(query)
                hideKeyboard(binding.quickSearch)
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                filterCurrentItems(newText)
                return true
            }
        })

        if (isLayout(PHONE or EMULATOR)) {
            binding.quickSearchBack.apply {
                isVisible = true
                setOnClickListener {
                    activity?.popCurrentPage()
                }
            }
        }

        // Initial Load: Movies
        currentCategory = CategoryType.MOVIES
        currentPage = 1
        binding.chipCategoryMovies.isChecked = true
        loadCategoryContent(currentCategory, 1)
    }

    private fun filterCurrentItems(query: String) {
        if (query.isBlank()) {
            val qualityFiltered = context?.filterSearchResultByFilmQuality(allCategoryItems) ?: allCategoryItems
            searchAdapter?.submitList(qualityFiltered.toList())
        } else {
            val filtered = allCategoryItems.filter {
                it.name.contains(query, ignoreCase = true)
            }
            val qualityFiltered = context?.filterSearchResultByFilmQuality(filtered) ?: filtered
            searchAdapter?.submitList(qualityFiltered.toList())
        }
    }
}
