package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.*

class GhoststreamProvider : MainAPI() {
    override var mainUrl = "https://ghoststream.com"
    override var name = "Ghoststream"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val lang = "en"

    // Add all your requested websites as sources
    private val sources = listOf(
        "2embed.cc",
        "allanime.site", 
        "allmovieland.ws",
        "dramadrip.com",
        "kisskh.co",
        "kisskhasia.com",
        "multimovies.cc",
        "player4u.org", 
        "showflix.in",
        "vegamovies.nl",
        // Additional high-quality sources:
        "fmovies.to",
        "soap2day.rs",
        "movie4kto.net",
        "putlocker.li",
        "123moviesfree.net"
    )

    override suspend fun loadHomePage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        
        // Add sections for different types
        items.add(HomePageList("Latest Movies", getLatestMovies()))
        items.add(HomePageList("Popular TV Shows", getPopularTvShows()))
        items.add(HomePageList("Trending Anime", getTrendingAnime()))
        
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Search across all sources
        return sources.flatMap { source ->
            try {
                searchSource(source, query)
            } catch (e: Exception) {
                emptyList()
            }
        }.distinctBy { it.url }
    }

    private suspend fun getLatestMovies(): List<SearchResponse> {
        // Implementation for latest movies
        return emptyList() // You'll add actual scraping here
    }

    private suspend fun getPopularTvShows(): List<SearchResponse> {
        // Implementation for popular TV shows  
        return emptyList()
    }

    private suspend fun getTrendingAnime(): List<SearchResponse> {
        // Implementation for trending anime
        return emptyList()
    }

    private suspend fun searchSource(source: String, query: String): List<SearchResponse> {
        // Search implementation for each source
        val searchUrl = when (source) {
            "2embed.cc" -> "https://2embed.cc/search/$query"
            "vegamovies.nl" -> "https://vegamovies.nl/?s=$query"
            "fmovies.to" -> "https://fmovies.to/filter?keyword=$query"
            else -> "$source/search?q=$query"
        }
        
        val document = app.get(searchUrl).document
        return document.select("div.item, article.movie, .search-item").mapNotNull { element ->
            parseSearchResult(element, source)
        }
    }

    private fun parseSearchResult(element: Element, source: String): SearchResponse? {
        return try {
            val title = element.select("h2, .title, a").text()
            val href = element.select("a").attr("href")
            val poster = element.select("img").attr("src")
            
            MovieSearchResponse(
                name = title,
                url = "$source|$href", // Store source with URL
                apiName = name,
                type = TvType.Movie, // You can detect type from element
                posterUrl = poster
            )
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.split("|")
        if (parts.size != 2) return null
        
        val source = parts[0]
        val actualUrl = parts[1]
        
        return when (source) {
            "2embed.cc" -> load2Embed(actualUrl)
            "vegamovies.nl" -> loadVegamovies(actualUrl)
            "fmovies.to" -> loadFmovies(actualUrl)
            else -> loadGeneric(actualUrl)
        }
    }

    private suspend fun load2Embed(url: String): LoadResponse? {
        // 2Embed specific loading
        val document = app.get(url).document
        val title = document.select("h1").text()
        
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = document.select(".poster img").attr("src")
            this.plot = document.select(".plot").text()
        }
    }

    private suspend fun loadVegamovies(url: String): LoadResponse? {
        // Vegamovies specific loading
        val document = app.get(url).document
        val title = document.select("h1").text()
        
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = document.select(".thumbnail img").attr("src")
            this.plot = document.select(".content").text()
        }
    }

    private suspend fun loadFmovies(url: String): LoadResponse? {
        // Fmovies specific loading
        val document = app.get(url).document
        val title = document.select("h1").text()
        
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = document.select(".poster img").attr("src")
            this.plot = document.select(".desc").text()
        }
    }

    private suspend fun loadGeneric(url: String): LoadResponse? {
        // Generic loading for other sources
        val document = app.get(url).document
        val title = document.select("h1").firstOrNull()?.text() ?: return null
        
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = document.select("img").firstOrNull()?.attr("src")
            this.plot = document.select("p, .description, .plot").firstOrNull()?.text()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size != 2) return false
        
        val source = parts[0]
        val url = parts[1]
        
        // Use appropriate extractors for each source
        when (source) {
            "2embed.cc" -> {
                val extractor = TwoEmbedExtractor()
                extractor.getUrl(url, subtitleCallback, callback)
            }
            "vegamovies.nl" -> {
                val extractor = StreamTape()
                extractor.getUrl(url, subtitleCallback, callback)  
            }
            "fmovies.to" -> {
                val extractor = Mp4Upload()
                extractor.getUrl(url, subtitleCallback, callback)
            }
            else -> {
                // Try common extractors
                val extractors = listOf(StreamTape(), Mp4Upload(), DoodLaExtractor())
                extractors.any { it.getUrl(url, subtitleCallback, callback) }
            }
        }
        
        return true
    }
}
