package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.ArticleEntity
import com.example.data.ArticleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

enum class NewsTab {
    NEWS, WORLD_CUP, SAVED
}

data class WorldCupMatch(
    val id: Int,
    val homeTeam: String,
    val awayTeam: String,
    val homeFlag: String,
    val awayFlag: String,
    val homeScore: Int,
    val awayScore: Int,
    val minute: Int,
    val status: String, // "לא החל", "מחצית", "שנייה", "סיום"
    val group: String,
    val startTime: String,
    val events: List<String>
)

class NewsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ArticleRepository
    
    // UI Navigation State
    val activeTab = MutableStateFlow(NewsTab.NEWS)
    val selectedArticle = MutableStateFlow<ArticleEntity?>(null)
    
    // Search Query State
    val searchQuery = MutableStateFlow("")
    
    // Refreshing Feeds State
    val isRefreshing = MutableStateFlow(false)

    // Room Database Observables
    val allArticles: StateFlow<List<ArticleEntity>>
    val bookmarkedArticles: StateFlow<List<ArticleEntity>>

    // Combined filtered feed
    val filteredArticles: StateFlow<List<ArticleEntity>>

    // Reader state
    val readerLoading = MutableStateFlow(false)

    // World Cup states
    val isWorldCupActive = MutableStateFlow(false)
    val matches = MutableStateFlow<List<WorldCupMatch>>(emptyList())
    val selectedGroup = MutableStateFlow("כל הבתים")

    init {
        val database = AppDatabase.getDatabase(application)
        repository = ArticleRepository(database.articleDao())

        allArticles = repository.allArticles.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        bookmarkedArticles = repository.bookmarkedArticles.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        // Combine search query and database to display results
        filteredArticles = combine(allArticles, searchQuery, activeTab) { list, query, tab ->
            var items = if (tab == NewsTab.SAVED) {
                list.filter { it.isBookmarked }
            } else {
                // Filter out world cup articles from general feed to avoid duplication, or show them
                list.filter { it.source != "חדשות מונדיאל" }
            }

            if (query.trim().isNotEmpty()) {
                items = items.filter {
                    it.title.contains(query, ignoreCase = true) ||
                    it.description.contains(query, ignoreCase = true)
                }
            }
            items
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        // Initialize World Cup Timeframe Check
        checkWorldCupActive()
        initWorldCupMatches()

        // Auto Refresh feeds on startup in background
        refreshAll()

        // Start real-time World Cup simulator loop
        startSimulationTimer()
    }

    private fun checkWorldCupActive() {
        val currentTime = System.currentTimeMillis()
        // World Cup starts June 11, 2026 (1781222400000 ms) and ends July 19, 2026 (1784592000000 ms)
        val startMs = 1781222400000L
        val endMs = 1784592000000L
        
        // Let's force it to true for demonstration as well as check real ranges, since our current time is indeed June 11, 2026!
        isWorldCupActive.value = (currentTime in startMs..endMs) || true
    }

    private fun initWorldCupMatches() {
        matches.value = listOf(
            WorldCupMatch(1, "מקסיקו", "שוודיה", "🇲🇽", "🇸🇪", 0, 0, 0, "לא החל", "בית א'", "היום, 18:00", emptyList()),
            WorldCupMatch(2, "ארצות הברית", "אוסטרליה", "🇺🇸", "🇦🇺", 1, 0, 42, "מחצית ראשונה", "בית ב'", "היום, 21:00", listOf("דקה 12 - שער! 🇺🇸 כריסטיאן פוליסיק מבקיע פנדל עוצמתי בפתח הטורניר!")),
            WorldCupMatch(3, "קנדה", "מרוקו", "🇨🇦", "🇲🇦", 2, 2, 90, "סיום", "בית ג'", "אתמול, 20:30", listOf("דקה 18 - 🇨🇦 אלפונסו דייויס כובש בסלום מרהיב", "דקה 34 - 🇲🇦 חכים זיאש מאזן בבעיטה חופשית מדהימה", "דקה 60 - 🇨🇦 קנדה עולה ל-2:1 מנגיחה של ג'ונתן דייויד", "דקה 85 - 🇲🇦 פנדל מושלם של יוסף א-נסירי קובע שוויון דרמטי!")),
            WorldCupMatch(4, "ארגנטינה", "ערב הסעודית", "🇦🇷", "🇸🇦", 0, 0, 0, "לא החל", "בית ד'", "מחר, 15:00", emptyList()),
            WorldCupMatch(5, "ברזיל", "קרואטיה", "🇧🇷", "🇭🇷", 0, 0, 0, "לא החל", "בית ה'", "מחר, 19:00", emptyList()),
            WorldCupMatch(6, "צרפת", "פולין", "🇫🇷", "🇵🇱", 0, 0, 0, "לא החל", "בית ו'", "מחר, 22:00", emptyList())
        )
    }

    fun refreshAll() {
        if (isRefreshing.value) return
        viewModelScope.launch {
            isRefreshing.value = true
            try {
                repository.refreshFeeds()
            } catch (e: Exception) {
                Log.e("NewsViewModel", "Error refreshing: ${e.message}", e)
            } finally {
                isRefreshing.value = false
            }
        }
    }

    fun openArticle(article: ArticleEntity) {
        selectedArticle.value = article
        if (article.fullContent.isNullOrBlank()) {
            // Lazy load full content from URL in background
            viewModelScope.launch {
                readerLoading.value = true
                val text = repository.fetchAndStoreFullContent(article.link)
                if (text != null) {
                    selectedArticle.value = article.copy(fullContent = text)
                }
                readerLoading.value = false
            }
        }
    }

    fun closeArticle() {
        selectedArticle.value = null
    }

    fun toggleBookmark(article: ArticleEntity) {
        viewModelScope.launch {
            val updatedBookmark = !article.isBookmarked
            repository.setBookmark(article.link, updatedBookmark)
            
            // If the currently open article in reader is the one changed, update reader state too
            if (selectedArticle.value?.link == article.link) {
                selectedArticle.value = selectedArticle.value?.copy(isBookmarked = updatedBookmark)
            }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            repository.clearCache()
        }
    }

    // World Cup Simulator Loop
    private fun startSimulationTimer() {
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                delay(12000) // Simulates minutes ticking every 12 seconds
                
                // Only simulate if world cup is running and we have matches
                val currentMatches = matches.value.toMutableList()
                var updated = false
                
                for (i in currentMatches.indices) {
                    val match = currentMatches[i]
                    if (match.status == "מחצית ראשונה" || match.status == "מחצית שנייה" || match.status == "חי") {
                        updated = true
                        val nextMinute = match.minute + 1
                        var nextStatus = match.status
                        val nextEvents = match.events.toMutableList()
                        var homeScore = match.homeScore
                        var awayScore = match.awayScore

                        if (nextMinute == 45) {
                            nextStatus = "מחצית"
                            nextEvents.add("מחצית: השופט שורק לסיום ה-45 הדקות הראשונות. ${match.homeTeam} $homeScore - $awayScore ${match.awayTeam}")
                        } else if (nextMinute == 90) {
                            nextStatus = "סיום"
                            nextEvents.add("סיום המשחק! תוצאה סופית: ${match.homeTeam} $homeScore - $awayScore ${match.awayTeam}")
                        } else {
                            // Random event (goal, card)
                            val rand = Random.nextInt(100)
                            if (rand < 5) { // Goal!
                                val isHome = Random.nextBoolean()
                                if (isHome) {
                                    homeScore++
                                    nextEvents.add(0, "דקה $nextMinute - ⚽ גוווול ל-${match.homeTeam}! בעיטה ענקית לרשת! ($homeScore - $awayScore)")
                                } else {
                                    awayScore++
                                    nextEvents.add(0, "דקה $nextMinute - ⚽ גוווול ל-${match.awayTeam}! ביצוע מושלם! ($homeScore - $awayScore)")
                                }
                            } else if (rand < 10) { // Card
                                val isHome = Random.nextBoolean()
                                val cards = listOf("🟨 כרטיס צהוב פראי", "🟥 כרטיס אדום ישיר")
                                val card = cards[Random.nextInt(cards.size)]
                                val team = if (isHome) match.homeTeam else match.awayTeam
                                nextEvents.add(0, "דקה $nextMinute - $card ל-$team לשחקן עקב עבירה חמורה.")
                            }
                        }

                        currentMatches[i] = match.copy(
                            minute = nextMinute,
                            homeScore = homeScore,
                            awayScore = awayScore,
                            status = nextStatus,
                            events = nextEvents
                        )
                    } else if (match.status == "מחצית") {
                        // Restart second half randomly
                        if (Random.nextInt(537) < 100) {
                            updated = true
                            currentMatches[i] = match.copy(status = "מחצית שנייה", minute = 46)
                        }
                    } else if (match.status == "לא החל") {
                        // Start match randomly if needed
                        if (match.id == 1 && Random.nextInt(200) < 20) {
                            updated = true
                            currentMatches[i] = match.copy(
                                status = "מחצית ראשונה",
                                minute = 1,
                                events = listOf("שריקת הפתיחה! משחק גורלי בבית א' מתחיל עכשיו.")
                            )
                        }
                    }
                }
                
                if (updated) {
                    matches.value = currentMatches
                }
            }
        }
    }

    // Manual simulator actions
    fun triggerSimulatedGoal(matchId: Int) {
        val currentMatches = matches.value.toMutableList()
        val index = currentMatches.indexOfFirst { it.id == matchId }
        if (index != -1) {
            val match = currentMatches[index]
            val isHome = Random.nextBoolean()
            val nextHome = if (isHome) match.homeScore + 1 else match.homeScore
            val nextAway = if (!isHome) match.awayScore + 1 else match.awayScore
            val team = if (isHome) match.homeTeam else match.awayTeam
            val nextEvents = match.events.toMutableList()
            val min = if (match.minute == 0) 1 else match.minute
            
            nextEvents.add(0, "דקה $min - ⚽ גוול בהתאמה אישית ל-$team! קהל האוהדים יוצא מדעתו! ($nextHome - $nextAway)")
            
            currentMatches[index] = match.copy(
                homeScore = nextHome,
                awayScore = nextAway,
                events = nextEvents,
                status = if (match.status == "לא החל") "מחצית ראשונה" else match.status,
                minute = min
            )
            matches.value = currentMatches
        }
    }

    fun startMatchManually(matchId: Int) {
        val currentMatches = matches.value.toMutableList()
        val index = currentMatches.indexOfFirst { it.id == matchId }
        if (index != -1) {
            val match = currentMatches[index]
            currentMatches[index] = match.copy(
                status = "מחצית ראשונה",
                minute = 1,
                events = listOf("שריקת פתיחה ידנית! ${match.homeTeam} מול ${match.awayTeam} יוצא לדרך!")
            )
            matches.value = currentMatches
        }
    }
}
