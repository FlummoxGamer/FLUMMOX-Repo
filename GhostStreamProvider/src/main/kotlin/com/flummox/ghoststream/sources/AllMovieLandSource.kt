package com.flummox.ghoststream.sources

import com.lagacy.models.*
import com.lagacy.utils.*
import okhttp3.FormBody
import org.jsoup.nodes.Element

class AllMovieLandSource : GhostStreamSource {
    override val name = "AllMovieLand"
    override val baseUrl = "https://allmovieland.ac"  // Changed to .ac as confirmed working
    
    private var playerDomain: String? = null
    private var cookies = mapOf<String, String>()

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            // Get cookies first
            initializeCookies()
            
            val body = FormBody.Builder()
                .addEncoded("do", "search")
                .addEncoded("subaction", "search") 
                .addEncoded("search_start", "0")
                .addEncoded("full_search", "0")
                .addEncoded("result_from", "1")
                .addEncoded("story", query)
                .build()

            val response = app.post(
                "$baseUrl/index.php?do=opensearch",
                requestBody = body,
                referer = "$baseUrl/",
                cookies = cookies
            )

            response.document.select("article.short-mid").mapNotNull {
                it.toSearchResult()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getMainPage(): List<HomePageList> {
        return try {
            initializeCookies()
            
            val categories = listOf(
                "$baseUrl/films/" to "Movies",
                "$baseUrl/series/" to "TV Shows"
            )

            val homePageLists = mutableListOf<HomePageList>()
            
            for ((url, categoryName) in categories) {
                try {
                    val document = app.get(url, cookies = cookies).document
                    val items = document.select("article.short-mid").mapNotNull {
                        it.toHomeSearchResult()
                    }
                    if (items.isNotEmpty()) {
                        homePageLists.add(HomePageList(categoryName, items))
                    }
                } catch (e: Exception) {
                    continue
                }
            }
            
            homePageLists
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun loadContent(id: String): LoadResponse? {
        return try {
            val doc = app.get(id).document
            val title = doc.selectFirst("h1.fs__title")?.text()?.trim() ?: return null
            val poster = fixUrlNull(doc.selectFirst("img.fs__poster-img")?.attr("src"))
            val description = doc.select("div.fs__descr--text > p").text().trim()
            
            // Extract year from title if available
            val yearRegex = Regex("""(\d{4})""")
            val year = yearRegex.find(title)?.value?.toIntOrNull()
            
            // Determine content type from URL or other
