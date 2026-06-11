package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ArticleDao {
    @Query("SELECT * FROM articles ORDER BY cachedAt DESC")
    fun getAllArticlesFlow(): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles WHERE isBookmarked = 1 ORDER BY cachedAt DESC")
    fun getBookmarkedArticlesFlow(): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles WHERE link = :link LIMIT 1")
    suspend fun getArticleByLink(link: String): ArticleEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArticles(articles: List<ArticleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(article: ArticleEntity)

    @Update
    suspend fun updateArticle(article: ArticleEntity)

    @Query("UPDATE articles SET isBookmarked = :bookmark WHERE link = :link")
    suspend fun setBookmark(link: String, bookmark: Boolean)

    @Query("DELETE FROM articles WHERE link = :link")
    suspend fun deleteArticleByLink(link: String)

    @Query("DELETE FROM articles WHERE isBookmarked = 0")
    suspend fun clearUnbookmarkedCache()
}
