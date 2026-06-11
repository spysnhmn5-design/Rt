package com.example.data

import android.util.Log
import com.example.network.RssFeedFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class ArticleRepository(private val articleDao: ArticleDao) {

    val allArticles: Flow<List<ArticleEntity>> = articleDao.getAllArticlesFlow()
    val bookmarkedArticles: Flow<List<ArticleEntity>> = articleDao.getBookmarkedArticlesFlow()

    suspend fun getArticleByLink(link: String): ArticleEntity? {
        return withContext(Dispatchers.IO) {
            articleDao.getArticleByLink(link)
        }
    }

    suspend fun setBookmark(link: String, isBookmarked: Boolean) {
        withContext(Dispatchers.IO) {
            articleDao.setBookmark(link, isBookmarked)
        }
    }

    suspend fun clearCache() {
        withContext(Dispatchers.IO) {
            articleDao.clearUnbookmarkedCache()
        }
    }

    suspend fun fetchAndStoreFullContent(link: String): String? {
        return withContext(Dispatchers.IO) {
            val fullText = RssFeedFetcher.fetchFullArticleText(link)
            if (fullText != null) {
                val existing = articleDao.getArticleByLink(link)
                if (existing != null) {
                    articleDao.insertOrReplace(existing.copy(fullContent = fullText))
                }
            }
            fullText
        }
    }

    suspend fun refreshFeeds() {
        withContext(Dispatchers.IO) {
            Log.d("ArticleRepository", "Refreshing news feeds...")
            
            val feeds = listOf(
                Pair("https://www.now14.co.il/feed/", "עכשיו 14"),
                Pair("https://news.google.com/rss?hl=he&gl=IL&ceid=IL:he", "גוגל חדשות"),
                // Special Google News World Cup Search
                Pair("https://news.google.com/rss/search?q=%D7%9E%D7%95%D7%A0%D7%93%D7%99%D7%90%D7%9C+2026&hl=he&gl=IL&ceid=IL:he", "חדשות מונדיאל")
            )

            val fetchedEntities = mutableListOf<ArticleEntity>()

            for ((url, source) in feeds) {
                try {
                    val items = RssFeedFetcher.fetchAndParseFeed(url, source)
                    Log.d("ArticleRepository", "Fetched ${items.size} articles from $source")
                    
                    val entities = items.map { item ->
                        // Check if we already have this article locally to preserve bookmarked status and content
                        val existing = articleDao.getArticleByLink(item.link)
                        ArticleEntity(
                            link = item.link,
                            title = item.title,
                            description = item.description,
                            pubDate = item.pubDate,
                            source = item.source,
                            imageUrl = item.imageUrl,
                            fullContent = existing?.fullContent,
                            isBookmarked = existing?.isBookmarked ?: false,
                            cachedAt = existing?.cachedAt ?: System.currentTimeMillis()
                        )
                    }
                    fetchedEntities.addAll(entities)
                } catch (e: Exception) {
                    Log.e("ArticleRepository", "Error loading feed $source: ${e.message}", e)
                }
            }

            if (fetchedEntities.isNotEmpty()) {
                // Insert list elements individually or run a safe transactions setup
                for (entity in fetchedEntities) {
                    articleDao.insertOrReplace(entity)
                }
                Log.d("ArticleRepository", "Successfully stored ${fetchedEntities.size} articles in database.")
            }
        }
    }
}
