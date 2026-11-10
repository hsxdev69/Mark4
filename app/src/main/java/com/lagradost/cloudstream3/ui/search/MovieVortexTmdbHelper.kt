package com.lagradost.cloudstream3.ui.search

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import org.json.JSONObject

object MovieVortexTmdbHelper {
    private const val TMDB_SEARCH_URL = "https://api.themoviedb.org/3/search/multi"
    private const val TMDB_API_KEY = "e6333b32409e02a4a6eba6fb7ff866bb"
    private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w300"

    suspend fun getCastForQuery(query: String): Pair<String?, List<StarCastItem>> {
        if (query.isBlank() || query.length < 2) return null to emptyList()
        return try {
            val searchRes = app.get(
                TMDB_SEARCH_URL,
                params = mapOf(
                    "api_key" to TMDB_API_KEY,
                    "query" to query,
                    "language" to "en-US"
                ),
                cacheTime = 60 * 24
            )
            val json = JSONObject(searchRes.text)
            val results = json.optJSONArray("results") ?: return null to emptyList()
            var targetId: Int? = null
            var targetType: String? = null
            var targetTitle: String? = null

            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val mediaType = item.optString("media_type")
                if (mediaType == "movie" || mediaType == "tv") {
                    targetId = item.optInt("id")
                    targetType = mediaType
                    targetTitle = item.optString("title").ifBlank { item.optString("name") }
                    break
                }
            }

            if (targetId == null || targetType == null) return null to emptyList()

            val creditsUrl = "https://api.themoviedb.org/3///credits"
            val creditsRes = app.get(
                creditsUrl,
                params = mapOf(
                    "api_key" to TMDB_API_KEY,
                    "language" to "en-US"
                ),
                cacheTime = 60 * 24
            )
            val creditsJson = JSONObject(creditsRes.text)
            val castArray = creditsJson.optJSONArray("cast") ?: return targetTitle to emptyList()
            val list = mutableListOf<StarCastItem>()
            for (i in 0 until minOf(castArray.length(), 15)) {
                val actor = castArray.optJSONObject(i) ?: continue
                val name = actor.optString("name")
                val character = actor.optString("character")
                val profilePath = actor.optString("profile_path")
                if (name.isNotBlank() && profilePath.isNotBlank()) {
                    list.add(
                        StarCastItem(
                            name = name,
                            profileUrl = "",
                            role = character
                        )
                    )
                }
            }
            targetTitle to list
        } catch (e: Exception) {
            logError(e)
            null to emptyList()
        }
    }
}
