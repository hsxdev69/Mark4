package com.lagradost.cloudstream3.ui.search

import android.app.Activity
import android.content.Intent
import android.content.DialogInterface
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.doOnLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.lagradost.cloudstream3.databinding.MovievortexCategoryChipBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.MovieSearchResponse
import org.json.JSONObject
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKeys
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.databinding.FragmentSearchBinding
import com.lagradost.cloudstream3.databinding.HomeSelectMainpageBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.BaseAdapter
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.home.HomeFragment
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.bindChips
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.currentSpan
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.loadHomepageList
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.updateChips
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.home.ParentItemAdapter
import com.lagradost.cloudstream3.ui.result.FOCUS_SELF
import com.lagradost.cloudstream3.ui.result.setLinearListLayout
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.AppContextUtils.filterSearchResultByFilmQuality
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiProviderLangSettings
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiSettings
import com.lagradost.cloudstream3.utils.AppContextUtils.ownHide
import com.lagradost.cloudstream3.utils.AppContextUtils.ownShow
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.attachBackPressedCallback
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.detachBackPressedCallback
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.getSpanCount
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock

class SearchFragment : BaseFragment<FragmentSearchBinding>(
    BaseFragment.BindingCreator.Bind(FragmentSearchBinding::bind)
) {
    companion object {
        fun List<SearchResponse>.filterSearchResponse(): List<SearchResponse> {
            return this.filter { response ->
                if (response is AnimeSearchResponse) {
                    val status = response.dubStatus
                    (status.isNullOrEmpty()) || (status.any {
                        APIRepository.dubStatusActive.contains(it)
                    })
                } else {
                    true
                }
            }
        }

        const val MOVIEVORTEX_RECENT_SEARCHES_KEY = "movievortex_recent_media_searches"
        const val SEARCH_QUERY = "search_query"

        fun newInstance(query: String): Bundle {
            return Bundle().apply {
                if (query.isNotBlank()) putString(SEARCH_QUERY, query)
            }
        }
    }

    private val searchViewModel: SearchViewModel by activityViewModels()
    private var bottomSheetDialog: BottomSheetDialog? = null
    private var realTimeSearchJob: Job? = null
    private var dynamicCastJob: Job? = null

    private val speechRecognizerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                val matches = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                if (!matches.isNullOrEmpty()) {
                    val recognizedText = matches[0]
                    binding?.mainSearch?.setQuery(recognizedText, true)
                }
            }
        }

    override fun pickLayout(): Int? =
        if (isLayout(TV or EMULATOR)) R.layout.fragment_search_tv else R.layout.fragment_search

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        activity?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
        bottomSheetDialog?.ownShow()
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onDestroyView() {
        dynamicCastJob?.cancel()
        realTimeSearchJob?.cancel()
        hideKeyboard()
        bottomSheetDialog?.ownHide()
        activity?.detachBackPressedCallback("SearchFragment")
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        searchViewModel.clearSuggestions()
        afterPluginsLoadedEvent += ::reloadRepos
    }

    override fun onStop() {
        super.onStop()
        afterPluginsLoadedEvent -= ::reloadRepos
    }

    var selectedSearchTypes = mutableListOf<TvType>()
    var selectedApis = mutableSetOf<String>()

    /**
     * Will filter all providers by preferred media and selectedSearchTypes.
     * If that results in no available providers then only filter
     * providers by preferred media
     **/
    fun search(query: String?) {
        if (query == null) return
        searchViewModel.clearSuggestions()
        // don't resume state from prev search
        (binding?.searchMasterRecycler?.adapter as? BaseAdapter<*, *>)?.clearState()
        context?.let { ctx ->
            val default = enumValues<TvType>().sorted().filter { it != TvType.NSFW }
                .map { it.ordinal.toString() }.toSet()
            val preferredTypes = (PreferenceManager.getDefaultSharedPreferences(ctx)
                .getStringSet(this.getString(R.string.prefer_media_type_key), default)
                ?.ifEmpty { default } ?: default)
                .mapNotNull { it.toIntOrNull() ?: return@mapNotNull null }

            val settings = ctx.getApiSettings()

            val notFilteredBySelectedTypes = selectedApis.filter { name ->
                settings.contains(name)
            }.map { name ->
                name to getApiFromNameNull(name)?.supportedTypes
            }.filter { (_, types) ->
                types?.any { preferredTypes.contains(it.ordinal) } == true
            }

            searchViewModel.searchAndCancel(
                query = query,
                providersActive = notFilteredBySelectedTypes.filter { (_, types) ->
                    types?.any { selectedSearchTypes.contains(it) } == true
                }.ifEmpty { notFilteredBySelectedTypes }.map { it.first }.toSet()
            )
        }
    }

    // Null if defined as a variable
    // This needs to be run after view created

    private fun reloadRepos(success: Boolean = false) = main {
        searchViewModel.reloadRepos()
        context?.filterProviderByPreferredMedia()?.let { validAPIs ->
            bindChips(
                binding?.tvtypesChipsScroll?.tvtypesChips,
                selectedSearchTypes,
                validAPIs.flatMap { api -> api.supportedTypes }.distinct()
            ) { list ->
                if (selectedSearchTypes.toSet() != list.toSet()) {
                    DataStoreHelper.searchPreferenceTags = list
                    selectedSearchTypes.clear()
                    selectedSearchTypes.addAll(list)
                    search(binding?.mainSearch?.query?.toString())
                }
            }
        }
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(
            view,
            padBottom = isLandscape(),
            padLeft = isLayout(TV or EMULATOR)
        )

        // Fix grid
        currentSpan = view.context.getSpanCount()
        binding?.searchAutofitResults?.spanCount = currentSpan
        HomeFragment.configEvent.invoke()
    }

    private fun initDiscoveryView(binding: FragmentSearchBinding) {
        val recentAdapter = RecentSearchMediaAdapter { item ->
            if (item.searchResponse != null) {
                SearchHelper.handleSearchClickCallback(
                    SearchClickCallback(
                        SEARCH_ACTION_LOAD,
                        binding.root,
                        0,
                        item.searchResponse
                    )
                )
            } else {
                binding.mainSearch.setQuery(item.title, true)
            }
        }
        binding.recentSearchRecycler.adapter = recentAdapter

        val starCastAdapter = StarCastAdapter { actor ->
            binding.mainSearch.setQuery(actor.name, true)
        }
        binding.actorsRecycler.adapter = starCastAdapter

        val popularAdapter = RecentSearchMediaAdapter { item ->
            binding.mainSearch.setQuery(item.title, true)
        }
        binding.popularSearchRecycler.adapter = popularAdapter

        // Populate Categories matching user's screenshot
        context?.let { ctx ->
            val inflater = LayoutInflater.from(ctx)
            val row1 = listOf(
                "Biographical" to 932,
                "Western" to 673,
                "Documentary" to 126,
                "Action" to 1096,
                "Fantasy" to 475
            )
            val row2 = listOf(
                "Musical" to 398,
                "War" to 56,
                "Independent" to 49,
                "Crime" to 399,
                "Sci-fi" to 821
            )
            val row3 = listOf(
                "Comedy" to 736,
                "Horror" to 412,
                "Romance" to 580,
                "Animation" to 620,
                "Thriller" to 750
            )

            binding.categoriesRow1.removeAllViews()
            binding.categoriesRow2.removeAllViews()
            binding.categoriesRow3.removeAllViews()

            fun addChipsToRow(rowLayout: LinearLayout, list: List<Pair<String, Int>>) {
                for ((name, count) in list) {
                    val chipBinding = MovievortexCategoryChipBinding.inflate(inflater, rowLayout, false)
                    chipBinding.chipName.text = name
                    chipBinding.chipCount.text = count.toString()
                    chipBinding.root.setOnClickListener {
                        binding.mainSearch.setQuery(name, true)
                    }
                    rowLayout.addView(chipBinding.root)
                }
            }

            addChipsToRow(binding.categoriesRow1, row1)
            addChipsToRow(binding.categoriesRow2, row2)
            addChipsToRow(binding.categoriesRow3, row3)
        }

        // Populate Star Cast ("Based on actor" from user request)
        val starCastList = listOf(
            StarCastItem("Leonardo DiCaprio", "https://image.tmdb.org/t/p/w300/wo2hJpn04vbtmh0B9utCFdsQhxM.jpg"),
            StarCastItem("Scarlett Johansson", "https://image.tmdb.org/t/p/w300/TiJIJGNWk0r1Y4bYuB50clneUd.jpg"),
            StarCastItem("George Clooney", "https://image.tmdb.org/t/p/w300/kHiVvbh2zb5J8cR4XmBvX4v2H7C.jpg"),
            StarCastItem("Johnny Depp", "https://image.tmdb.org/t/p/w300/ca3x0OfIKbJpp28SqaLEr42mrY8.jpg"),
            StarCastItem("Cillian Murphy", "https://image.tmdb.org/t/p/w300/dm6V2iqUea2ZEmxkn52F942Bpt2.jpg"),
            StarCastItem("Tom Cruise", "https://image.tmdb.org/t/p/w300/8qB9PiZi4agqaz9U7HgiyZvgqdE.jpg"),
            StarCastItem("Margot Robbie", "https://image.tmdb.org/t/p/w300/euDPyqLnuwaWMHajcU3oZ9uZezR.jpg"),
            StarCastItem("Robert Downey Jr.", "https://image.tmdb.org/t/p/w300/5qHNjhtjMD4YWH3vi0lZ2947ggw.jpg"),
            StarCastItem("Christian Bale", "https://image.tmdb.org/t/p/w300/b7fTC9WFkgqGOv77mLQegGR5YX1.jpg"),
            StarCastItem("Zendaya", "https://image.tmdb.org/t/p/w300/r2GQ11Kx9B2a7v8A31f4H6gY7fV.jpg"),
            StarCastItem("Brad Pitt", "https://image.tmdb.org/t/p/w300/cckcYc2v0yh1tc9QjRelptsqRFQ.jpg"),
            StarCastItem("Keanu Reeves", "https://image.tmdb.org/t/p/w300/4D0PpNI0kmP58hgrwGC3wC5x0Vy.jpg"),
            StarCastItem("Ryan Gosling", "https://image.tmdb.org/t/p/w300/lyUyVARlaPrPVdmQ96al9OqRIHg.jpg"),
            StarCastItem("Shah Rukh Khan", "https://image.tmdb.org/t/p/w300/1Xdd36XgqM8u5aW9y3B9Y4bN9N.jpg")
        )
        starCastAdapter.submitList(starCastList)

        // Populate Popular Search
        val popularList = listOf(
            RecentMediaItem("Blade Runner 2049", "Movie", "https://image.tmdb.org/t/p/w300/gajva2L0rPYkEWjzgFlBXCAVBE5.jpg"),
            RecentMediaItem("Interstellar", "Movie", "https://image.tmdb.org/t/p/w300/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg"),
            RecentMediaItem("Dune: Part Two", "Movie", "https://image.tmdb.org/t/p/w300/1pdfLvkbY9ohJlCjQH2CZjjYVvJ.jpg"),
            RecentMediaItem("Oppenheimer", "Movie", "https://image.tmdb.org/t/p/w300/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg"),
            RecentMediaItem("The Dark Knight", "Movie", "https://image.tmdb.org/t/p/w300/qJ2tW6WMUDux911r6m7haRef0WH.jpg"),
            RecentMediaItem("The Big 4", "TV Series", "https://image.tmdb.org/t/p/w300/r2JkrYYq6Xh4k6m4X7e2a9sU3k7.jpg"),
            RecentMediaItem("Breaking Bad", "TV Series", "https://image.tmdb.org/t/p/w300/ggFHVNu6YYI5L9pCfOacjizRGt.jpg"),
            RecentMediaItem("Stranger Things", "TV Series", "https://image.tmdb.org/t/p/w300/49WJfeN0moxb9IPfGn8AIqMGskD.jpg")
        )
        popularAdapter.submitList(popularList)

        // Load recent searches asynchronously
        loadRecentSearches(recentAdapter)

        binding.recentSearchSeeAll.setOnClickListener {
            binding.searchDiscoveryScroll.isGone = true
            binding.searchHistoryRecycler.isVisible = true
        }
    }

    private fun saveRecentMediaSearch(card: SearchResponse?) {
        if (card == null || card.name.isBlank()) return
        ioSafe {
            try {
                val key = "/"
                val currentList = getKey<Array<String>>(key)?.toMutableList() ?: mutableListOf()
                val jsonItem = JSONObject().apply {
                    put("title", card.name)
                    put("type", if (card.type == TvType.Movie) "Movie" else if (card.type == TvType.TvSeries) "TV Series" else "Movie")
                    put("posterUrl", card.posterUrl ?: "")
                    put("url", card.url)
                    put("apiName", card.apiName)
                }.toString()
                currentList.removeAll { str ->
                    try {
                        JSONObject(str).optString("title").equals(card.name, ignoreCase = true)
                    } catch (e: Exception) {
                        false
                    }
                }
                currentList.add(0, jsonItem)
                val trimmed = currentList.take(20).toTypedArray()
                setKey(key, trimmed)
            } catch (e: Exception) {
                logError(e)
            }
        }
    }

    private fun updateDynamicCast(query: String, binding: FragmentSearchBinding) {
        dynamicCastJob?.cancel()
        if (query.isBlank() || query.length < 2) {
            binding.searchLiveCastContainer.isGone = true
            return
        }
        dynamicCastJob = ioSafe {
            val (targetTitle, castList) = MovieVortexTmdbHelper.getCastForQuery(query)
            main {
                if (castList.isNotEmpty()) {
                    binding.searchLiveCastTitle.text = if (!targetTitle.isNullOrBlank()) {
                        "Star Cast: "
                    } else {
                        "Star Cast"
                    }
                    (binding.searchLiveCastRecycler.adapter as? StarCastAdapter)?.submitList(castList)
                    binding.searchLiveCastContainer.isVisible = true
                    (binding.actorsRecycler.adapter as? StarCastAdapter)?.submitList(castList)
                } else {
                    binding.searchLiveCastContainer.isGone = true
                }
            }
        }
    }

    private fun loadRecentSearches(adapter: RecentSearchMediaAdapter) {
        ioSafe {
            val key = "/"
            val savedStrings = getKey<Array<String>>(key) ?: emptyArray()
            val items = mutableListOf<RecentMediaItem>()
            for (str in savedStrings) {
                try {
                    val obj = JSONObject(str)
                    val title = obj.optString("title")
                    val typeStr = obj.optString("type", "Movie")
                    val posterUrl = obj.optString("posterUrl")
                    val url = obj.optString("url")
                    val apiName = obj.optString("apiName")
                    if (title.isNotBlank()) {
                        val resp = if (url.isNotBlank() && apiName.isNotBlank()) {
                            MovieSearchResponse(
                                name = title,
                                url = url,
                                apiName = apiName,
                                type = if (typeStr == "TV Series") TvType.TvSeries else TvType.Movie,
                                posterUrl = posterUrl.ifBlank { null }
                            )
                        } else null

                        items.add(
                            RecentMediaItem(
                                title = title,
                                type = typeStr,
                                posterUrl = posterUrl,
                                url = url,
                                apiName = apiName,
                                searchResponse = resp
                            )
                        )
                    }
                } catch (e: Exception) {
                    logError(e)
                }
            }

            if (items.isEmpty()) {
                val resumeList = HomeViewModel.getResumeWatching() ?: emptyList()
                if (resumeList.isNotEmpty()) {
                    items.addAll(
                        resumeList.take(10).map {
                            RecentMediaItem(
                                title = it.name,
                                type = if (it.type == TvType.Movie) "Movie" else "TV Series",
                                posterUrl = it.posterUrl ?: "",
                                searchResponse = it
                            )
                        }
                    )
                } else {
                    items.addAll(
                        listOf(
                            RecentMediaItem("Blade Runner 2049", "Movie", "https://image.tmdb.org/t/p/w300/gajva2L0rPYkEWjzgFlBXCAVBE5.jpg"),
                            RecentMediaItem("The Revenant", "Movie", "https://image.tmdb.org/t/p/w300/oXU30erIm58t34BsEL9b8qP94UW.jpg"),
                            RecentMediaItem("The Big 4", "TV Series", "https://image.tmdb.org/t/p/w300/r2JkrYYq6Xh4k6m4X7e2a9sU3k7.jpg"),
                            RecentMediaItem("Dune: Part Two", "Movie", "https://image.tmdb.org/t/p/w300/1pdfLvkbY9ohJlCjQH2CZjjYVvJ.jpg"),
                            RecentMediaItem("Oppenheimer", "Movie", "https://image.tmdb.org/t/p/w300/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg")
                        )
                    )
                }
            }

            main {
                adapter.submitList(items)
            }
        }
    }

    override fun onBindingCreated(
        binding: FragmentSearchBinding,
        savedInstanceState: Bundle?
    ) {
        reloadRepos()
        initDiscoveryView(binding)
        val liveCastAdapter = StarCastAdapter { actor ->
            binding.mainSearch.setQuery(actor.name, true)
        }
        binding.searchLiveCastRecycler.adapter = liveCastAdapter

        binding.apply {
            val adapter =
                SearchAdapter(
                    searchAutofitResults,
                ) { callback ->
                    saveRecentMediaSearch(callback.card)
                    SearchHelper.handleSearchClickCallback(callback)
                }

            searchRoot.findViewById<TextView>(androidx.appcompat.R.id.search_src_text)?.tag =
                "tv_no_focus_tag"
            searchAutofitResults.setRecycledViewPool(SearchAdapter.sharedPool)
            searchAutofitResults.adapter = adapter
            searchLoadingBar.alpha = 0f
        }

        binding.voiceSearch.setOnClickListener { searchView ->
            searchView?.context?.let { ctx ->
                try {
                    if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                        showToast(R.string.speech_recognition_unavailable)
                    } else {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                            )
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                            putExtra(
                                RecognizerIntent.EXTRA_PROMPT,
                                ctx.getString(R.string.begin_speaking)
                            )
                        }
                        speechRecognizerLauncher.launch(intent)
                    }
                } catch (_: Throwable) {
                    // launch may throw
                    showToast(R.string.speech_recognition_unavailable)
                }
            }
        }

        val searchExitIcon =
            binding.mainSearch.findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn)

        selectedApis = DataStoreHelper.searchPreferenceProviders.toMutableSet()

        binding.searchFilter.setOnClickListener { searchView ->
            searchView?.context?.let { ctx ->
                val validAPIs = ctx.filterProviderByPreferredMedia(hasHomePageIsRequired = false)
                var currentValidApis = listOf<MainAPI>()
                val currentSelectedApis = if (selectedApis.isEmpty()) validAPIs.map { it.name }
                    .toMutableSet() else selectedApis

                val builder =
                    BottomSheetDialog(ctx)

                builder.behavior.state = BottomSheetBehavior.STATE_EXPANDED

                val selectMainpageBinding: HomeSelectMainpageBinding =
                    HomeSelectMainpageBinding.inflate(
                        builder.layoutInflater,
                        null,
                        false
                    )
                builder.setContentView(selectMainpageBinding.root)
                builder.show()
                builder.let { dialog ->
                    val previousSelectedApis = selectedApis.toSet()
                    val previousSelectedSearchTypes = selectedSearchTypes.toSet()

                    val isMultiLang = ctx.getApiProviderLangSettings().let { set ->
                        set.size > 1 || set.contains(AllLanguagesName)
                    }

                    val cancelBtt = dialog.findViewById<MaterialButton>(R.id.cancel_btt)
                    val applyBtt = dialog.findViewById<MaterialButton>(R.id.apply_btt)

                    val listView = dialog.findViewById<ListView>(R.id.listview1)
                    val arrayAdapter = ArrayAdapter<String>(ctx, R.layout.sort_bottom_single_choice)
                    listView?.adapter = arrayAdapter
                    listView?.choiceMode = AbsListView.CHOICE_MODE_MULTIPLE

                    listView?.setOnItemClickListener { _, _, i, _ ->
                        if (currentValidApis.isNotEmpty()) {
                            val api = currentValidApis[i].name
                            if (currentSelectedApis.contains(api)) {
                                listView.setItemChecked(i, false)
                                currentSelectedApis -= api
                            } else {
                                listView.setItemChecked(i, true)
                                currentSelectedApis += api
                            }
                        }
                    }

                    fun updateList(types: List<TvType>) {
                        DataStoreHelper.searchPreferenceTags = types

                        arrayAdapter.clear()
                        currentValidApis = validAPIs.filter { api ->
                            api.supportedTypes.any {
                                types.contains(it)
                            }
                        }.sortedBy { it.name.lowercase() }

                        val names = currentValidApis.map {
                            if (isMultiLang) "${
                                SubtitleHelper.getFlagFromIso(
                                    it.lang
                                )?.plus(" ") ?: ""
                            }${it.name}" else it.name
                        }
                        for ((index, api) in currentValidApis.map { it.name }.withIndex()) {
                            listView?.setItemChecked(index, currentSelectedApis.contains(api))
                        }

                        //arrayAdapter.notifyDataSetChanged()
                        arrayAdapter.addAll(names)
                        arrayAdapter.notifyDataSetChanged()
                    }

                    bindChips(
                        selectMainpageBinding.tvtypesChipsScroll.tvtypesChips,
                        selectedSearchTypes,
                        validAPIs.flatMap { api -> api.supportedTypes }.distinct()
                    ) { list ->
                        updateList(list)

                        // refresh selected chips in main chips
                        if (selectedSearchTypes.toSet() != list.toSet()) {
                            selectedSearchTypes.clear()
                            selectedSearchTypes.addAll(list)
                            updateChips(
                                binding.tvtypesChipsScroll.tvtypesChips,
                                selectedSearchTypes
                            )

                        }
                    }


                    cancelBtt?.setOnClickListener {
                        dialog.dismissSafe()
                    }

                    cancelBtt?.setOnClickListener {
                        dialog.dismissSafe()
                    }

                    applyBtt?.setOnClickListener {
                        //if (currentApiName != selectedApiName) {
                        //    currentApiName?.let(callback)
                        //}
                        dialog.dismissSafe()
                    }

                    dialog.setOnDismissListener {
                        DataStoreHelper.searchPreferenceProviders = currentSelectedApis.toList()
                        selectedApis = currentSelectedApis

                        // run search when dialog is close
                        if (previousSelectedApis != selectedApis.toSet() || previousSelectedSearchTypes != selectedSearchTypes.toSet()) {
                            search(binding.mainSearch.query.toString())
                        }
                    }
                    updateList(selectedSearchTypes.toList())
                }
            }
        }

        val settingsManager = context?.let { PreferenceManager.getDefaultSharedPreferences(it) }
        val isAdvancedSearch = settingsManager?.getBoolean("advanced_search", true) ?: true
        val isSearchSuggestionsEnabled = settingsManager?.getBoolean("search_suggestions_enabled", true) ?: true

        selectedSearchTypes = DataStoreHelper.searchPreferenceTags.toMutableList()

        if (!isLayout(PHONE)) {
            binding.searchFilter.isFocusable = true
            binding.searchFilter.isFocusableInTouchMode = true
        }

        // Hide suggestions when search view loses focus (phone only)
        if (isLayout(PHONE)) {
            binding.mainSearch.setOnQueryTextFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    searchViewModel.clearSuggestions()
                }
            }
        }


        binding.mainSearch.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                realTimeSearchJob?.cancel()
                search(query)
                searchViewModel.clearSuggestions()
                updateDynamicCast(query, binding)

                binding.apply {
                    searchDiscoveryScroll.isGone = true
                    searchHistoryRecycler.isGone = true
                    searchMasterRecycler.isVisible = isAdvancedSearch
                    searchAutofitResults.isVisible = !isAdvancedSearch
                    searchSuggestionsRecycler.isGone = true
                }

                binding.mainSearch.let {
                    hideKeyboard(it)
                }

                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                realTimeSearchJob?.cancel()
                val isBlank = newText.isBlank()
                if (isBlank) {
                    dynamicCastJob?.cancel()
                    searchViewModel.clearSearch()
                    searchViewModel.updateHistory()
                    searchViewModel.clearSuggestions()
                    (binding.recentSearchRecycler.adapter as? RecentSearchMediaAdapter)?.let {
                        loadRecentSearches(it)
                    }
                    binding.apply {
                        searchLiveCastContainer.isGone = true
                        searchDiscoveryScroll.isVisible = true
                        searchAutofitResults.isGone = true
                        searchMasterRecycler.isGone = true
                        searchHistoryRecycler.isGone = true
                        searchSuggestionsRecycler.isGone = true
                    }
                } else {
                    if (isSearchSuggestionsEnabled) {
                        searchViewModel.fetchSuggestions(newText)
                    }
                    binding.apply {
                        searchDiscoveryScroll.isGone = true
                        searchHistoryRecycler.isGone = true
                        searchMasterRecycler.isVisible = isAdvancedSearch
                        searchAutofitResults.isVisible = !isAdvancedSearch
                        searchSuggestionsRecycler.isVisible = isSearchSuggestionsEnabled
                    }

                    if (newText.trim().length >= 2) {
                        updateDynamicCast(newText.trim(), binding)
                        realTimeSearchJob = ioSafe {
                            delay(350)
                            main {
                                search(newText.trim())
                            }
                        }
                    }
                }

                return true
            }
        })

        observe(searchViewModel.searchResponse) {
            when (it) {
                is Resource.Success -> {
                    it.value.let { data ->
                        val list = data.list
                        if (list.isNotEmpty()) {
                            (binding.searchAutofitResults.adapter as? SearchAdapter)?.submitList(
                                list
                            )
                        }
                    }
                    searchExitIcon?.alpha = 1f
                    binding.searchLoadingBar.alpha = 0f
                }

                is Resource.Failure -> {
                    // Toast.makeText(activity, "Server error", Toast.LENGTH_LONG).show()
                    searchExitIcon?.alpha = 1f
                    binding.searchLoadingBar.alpha = 0f
                }

                is Resource.Loading -> {
                    searchExitIcon?.alpha = 0f
                    binding.searchLoadingBar.alpha = 1f
                }
            }
        }

        val listLock = ReentrantLock()
        observe(searchViewModel.currentSearch) { list ->
            try {
                // https://stackoverflow.com/questions/6866238/concurrent-modification-exception-adding-to-an-arraylist
                listLock.lock()

                val pinnedOrder = DataStoreHelper.pinnedProviders.reversedArray()

                val sortedList = list.toList().sortedWith(compareBy { (providerName, _) ->
                    val index = pinnedOrder.indexOf(providerName)
                    if (index == -1) Int.MAX_VALUE else index
                })

                (binding.searchMasterRecycler.adapter as? ParentItemAdapter)?.apply {
                    val newItems = sortedList.map { (providerName, providerData) ->
                        val dataList = providerData.list
                        val dataListFiltered =
                            context?.filterSearchResultByFilmQuality(dataList) ?: dataList

                        val homePageList = HomePageList(
                            providerName,
                            dataListFiltered
                        )

                        HomeViewModel.ExpandableHomepageList(
                            homePageList,
                            providerData.currentPage,
                            providerData.hasNext
                        )
                    }

                    submitList(newItems)
                    //notifyDataSetChanged()
                }
            } catch (e: Exception) {
                logError(e)
            } finally {
                listLock.unlock()
            }
        }


        /*main_search.setOnQueryTextFocusChangeListener { _, b ->
            if (b) {
                // https://stackoverflow.com/questions/12022715/unable-to-show-keyboard-automatically-in-the-searchview
                showInputMethod(view.findFocus())
            }
        }*/
        //main_search.onActionViewExpanded()*/

        val masterAdapter =
            ParentItemAdapter(id = "masterAdapter".hashCode(), { callback ->
                saveRecentMediaSearch(callback.card)
                SearchHelper.handleSearchClickCallback(callback)
            }, { item ->
                bottomSheetDialog = activity?.loadHomepageList(item, dismissCallback = {
                    bottomSheetDialog = null
                }, expandCallback = { name -> searchViewModel.expandAndReturn(name) })
            }, expandCallback = { name ->
                ioSafe {
                    searchViewModel.expandAndReturn(name)
                }
            })

        val historyAdapter = SearchHistoryAdaptor { click ->
            val searchItem = click.item
            when (click.clickAction) {
                SEARCH_HISTORY_OPEN -> {
                    if (searchItem == null) return@SearchHistoryAdaptor
                    searchViewModel.clearSearch()
                    if (searchItem.type.isNotEmpty())
                        updateChips(
                            binding.tvtypesChipsScroll.tvtypesChips,
                            searchItem.type.toMutableList()
                        )
                    binding.mainSearch.setQuery(searchItem.searchText, true)
                }

                SEARCH_HISTORY_REMOVE -> {
                    if (searchItem == null) return@SearchHistoryAdaptor
                    removeKey("$currentAccount/$SEARCH_HISTORY_KEY", searchItem.key)
                    searchViewModel.updateHistory()
                }

                SEARCH_HISTORY_CLEAR -> {
                    // Show confirmation dialog (from footer button)
                    activity?.let { ctx ->
                        val builder: AlertDialog.Builder = AlertDialog.Builder(ctx)
                        val dialogClickListener =
                            DialogInterface.OnClickListener { _, which ->
                                when (which) {
                                    DialogInterface.BUTTON_POSITIVE -> {
                                        removeKeys("$currentAccount/$SEARCH_HISTORY_KEY")
                                        searchViewModel.updateHistory()
                                    }

                                    DialogInterface.BUTTON_NEGATIVE -> {
                                    }
                                }
                            }

                        try {
                            builder.setTitle(R.string.clear_history).setMessage(
                                ctx.getString(R.string.delete_message).format(
                                    ctx.getString(R.string.history)
                                )
                            )
                                .setPositiveButton(R.string.sort_clear, dialogClickListener)
                                .setNegativeButton(R.string.cancel, dialogClickListener)
                                .show().setDefaultFocus()
                        } catch (e: Exception) {
                            logError(e)
                        }
                    }
                }

                else -> {
                    // wth are you doing???
                }
            }
        }

        val suggestionAdapter = SearchSuggestionAdapter { callback ->
            when (callback.clickAction) {
                SEARCH_SUGGESTION_CLICK -> {
                    // Search directly
                    binding.mainSearch.setQuery(callback.suggestion, true)
                    searchViewModel.clearSuggestions()
                }
                SEARCH_SUGGESTION_FILL -> {
                    // Fill the search box without searching
                    binding.mainSearch.setQuery(callback.suggestion, false)
                }
                SEARCH_SUGGESTION_CLEAR -> {
                    // Clear suggestions (from footer button)
                    searchViewModel.clearSuggestions()
                }
            }
        }

        binding.apply {
            searchHistoryRecycler.adapter = historyAdapter
            searchHistoryRecycler.setLinearListLayout(isHorizontal = false, nextRight = FOCUS_SELF)
            //searchHistoryRecycler.layoutManager = GridLayoutManager(context, 1)

            // Setup suggestions RecyclerView
            searchSuggestionsRecycler.adapter = suggestionAdapter
            searchSuggestionsRecycler.layoutManager = LinearLayoutManager(context)

            searchMasterRecycler.setRecycledViewPool(ParentItemAdapter.sharedPool)
            searchMasterRecycler.adapter = masterAdapter
            //searchMasterRecycler.setLinearListLayout(isHorizontal = false, nextRight = FOCUS_SELF)

            searchMasterRecycler.layoutManager = GridLayoutManager(context, 1)

            // Automatically search the specified query, this allows the app search to launch from intent
            var sq =
                arguments?.getString(SEARCH_QUERY) ?: savedInstanceState?.getString(SEARCH_QUERY)
            if (sq.isNullOrBlank()) {
                sq = MainActivity.nextSearchQuery
            }

            sq?.let { query ->
                if (query.isBlank()) return@let

                // Queries are dropped if you are submitted before layout finishes
                mainSearch.doOnLayout {
                    mainSearch.setQuery(query, true)
                }
                // Clear the query as to not make it request the same query every time the page is opened
                arguments?.remove(SEARCH_QUERY)
                savedInstanceState?.remove(SEARCH_QUERY)
                MainActivity.nextSearchQuery = null
            }
        }

        observe(searchViewModel.currentHistory) { list ->
            (binding.searchHistoryRecycler.adapter as? SearchHistoryAdaptor?)?.submitList(list)
             // Scroll to top to show newest items (list is sorted by newest first)
            if (list.isNotEmpty()) {
                binding.searchHistoryRecycler.scrollToPosition(0)
            }
        }

        // Observe search suggestions
        observe(searchViewModel.searchSuggestions) { suggestions ->
            val hasSuggestions = suggestions.isNotEmpty()
            binding.searchSuggestionsRecycler.isVisible = hasSuggestions
            (binding.searchSuggestionsRecycler.adapter as? SearchSuggestionAdapter?)?.submitList(suggestions)

            // On non-phone layouts, redirect focus and handle back button
            if (!isLayout(PHONE)) {
                if (hasSuggestions) {
                    binding.tvtypesChipsScroll.tvtypesChips.root.nextFocusDownId = R.id.search_suggestions_recycler
                    // Attach back button callback to clear suggestions
                    activity?.attachBackPressedCallback("SearchFragment") {
                        searchViewModel.clearSuggestions()
                    }
                } else {
                    // Reset to default focus target (history)
                    binding.tvtypesChipsScroll.tvtypesChips.root.nextFocusDownId = R.id.search_history_recycler
                    // Detach back button callback when no suggestions
                    activity?.detachBackPressedCallback("SearchFragment")
                }
            }
        }

        searchViewModel.updateHistory()
    }
}
