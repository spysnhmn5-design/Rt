package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "articles")
data class ArticleEntity(
    @PrimaryKey val link: String,
    val title: String,
    val description: String,
    val pubDate: String,
    val source: String,
    val imageUrl: String?,
    val fullContent: String?,
    val isBookmarked: Boolean = false,
    val cachedAt: Long = System.currentTimeMillis()
)
