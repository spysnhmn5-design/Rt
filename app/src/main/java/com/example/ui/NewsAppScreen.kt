package com.example.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ArticleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsAppScreen(viewModel: NewsViewModel) {
    val activeTab by viewModel.activeTab.collectAsState()
    val filteredArticles by viewModel.filteredArticles.collectAsState()
    val bookmarkedArticles by viewModel.bookmarkedArticles.collectAsState()
    val allArticlesState by viewModel.allArticles.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedArticle by viewModel.selectedArticle.collectAsState()
    val isWorldCupActive by viewModel.isWorldCupActive.collectAsState()
    val matches by viewModel.matches.collectAsState()
    val currentGroupFilter by viewModel.selectedGroup.collectAsState()

    val context = LocalContext.current

    // Force RTL direction for Hebrew app, regardless of phone locale
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Scaffold(
                    topBar = {
                        Column {
                            // High brand TV header
                            HeaderBar(
                                isRefreshing = isRefreshing,
                                onRefresh = { viewModel.refreshAll() }
                            )
                            
                            // High-impact news ticker from top headlines
                            val headlines = allArticlesState.take(8).map { it.title }
                            TickerMarquee(headlines = if (headlines.isEmpty()) listOf("טוען מבזקים...", "חדשות חמות מסביב לשעון") else headlines)
                        }
                    },
                    bottomBar = {
                        BottomNavBar(
                            activeTab = activeTab,
                            isWorldCupActive = isWorldCupActive,
                            onTabSelected = { viewModel.activeTab.value = it }
                        )
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        AnimatedContent(
                            targetState = activeTab,
                            transitionSpec = {
                                fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(220))
                            },
                            label = "TabContent"
                        ) { tab ->
                            when (tab) {
                                NewsTab.NEWS -> {
                                    NewsListScreen(
                                        articles = filteredArticles,
                                        searchQuery = searchQuery,
                                        onSearchQueryChanged = { viewModel.searchQuery.value = it },
                                        onArticleClicked = { viewModel.openArticle(it) },
                                        onBookmarkToggled = { viewModel.toggleBookmark(it) }
                                    )
                                }
                                NewsTab.WORLD_CUP -> {
                                    WorldCupDashboard(
                                        worldCupArticles = allArticlesState.filter { it.source == "חדשות מונדיאל" },
                                        matches = matches,
                                        currentGroupFilter = currentGroupFilter,
                                        onGroupChanged = { viewModel.selectedGroup.value = it },
                                        onGoalAction = { viewModel.triggerSimulatedGoal(it) },
                                        onStartAction = { viewModel.startMatchManually(it) },
                                        onArticleClicked = { viewModel.openArticle(it) }
                                    )
                                }
                                NewsTab.SAVED -> {
                                    SavedArticlesScreen(
                                        articles = bookmarkedArticles,
                                        searchQuery = searchQuery,
                                        onSearchQueryChanged = { viewModel.searchQuery.value = it },
                                        onClearUnbookmarkedCache = { viewModel.clearCache() },
                                        onArticleClicked = { viewModel.openArticle(it) },
                                        onBookmarkToggled = { viewModel.toggleBookmark(it) }
                                    )
                                }
                            }
                        }
                    }
                }

                // Full Screen Article Reader Overlay
                selectedArticle?.let { article ->
                    ArticleReaderOverlay(
                        article = article,
                        isLoading = viewModel.readerLoading.collectAsState().value,
                        onClose = { viewModel.closeArticle() },
                        onToggleBookmark = { viewModel.toggleBookmark(article) },
                        onShare = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, article.title)
                                putExtra(Intent.EXTRA_TEXT, "${article.title}\n\n${article.link}")
                            }
                            context.startActivity(Intent.createChooser(intent, "שיתוף כתבה"))
                        }
                    )
                }
            }
        }
    }
}

// ------------------------------------
// Custom Network Image Loader
// ------------------------------------
@Composable
fun NetworkImage(url: String?, modifier: Modifier = Modifier) {
    var imageBitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var loadFailed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        if (!url.isNullOrBlank()) {
            withContext(Dispatchers.IO) {
                try {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val request = okhttp3.Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val bytes = response.body?.bytes()
                            if (bytes != null) {
                                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bitmap != null) {
                                    imageBitmap = bitmap.asImageBitmap()
                                } else {
                                    loadFailed = true
                                }
                            } else {
                                loadFailed = true
                            }
                        } else {
                            loadFailed = true
                        }
                    }
                } catch (e: Exception) {
                    loadFailed = true
                }
            }
        } else {
            loadFailed = true
        }
    }

    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap!!,
            contentDescription = "תמונת כתבה",
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        // Aesthetic Gradient Placeholder
        Box(
            modifier = modifier.background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF1E3A8A), Color(0xFF0F172A))
                )
            ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(32.dp).rotate(-45f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "חדשות LIVE",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Italic
                )
            }
        }
    }
}

// ------------------------------------
// Decorative Confetti Particles (World Cup)
// ------------------------------------
data class ConfettiParticle(
    var x: Float,
    var y: Float,
    val speed: Float,
    val size: Float,
    val color: Color,
    val isSoccerBall: Boolean
)

@Composable
fun WorldCupConfettiEffect(modifier: Modifier = Modifier) {
    val particles = remember {
        mutableStateListOf<ConfettiParticle>().apply {
            repeat(20) {
                add(
                    ConfettiParticle(
                        x = Random.nextFloat(),
                        y = Random.nextFloat() * -500f,
                        speed = Random.nextFloat() * 6f + 3f,
                        size = Random.nextFloat() * 12f + 8f,
                        color = listOf(Color(0xFFFFD700), Color(0xFF4CAF50), Color(0xFF2196F3), Color.White).random(),
                        isSoccerBall = Random.nextFloat() < 0.3f
                    )
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(16)
            for (i in particles.indices) {
                val p = particles[i]
                var nextY = p.y + p.speed
                var nextX = p.x + (Random.nextFloat() * 0.02f - 0.01f)
                if (nextY > 1500f) {
                    nextY = -100f
                    nextX = Random.nextFloat()
                }
                particles[i] = p.copy(x = nextX, y = nextY)
            }
        }
    }

    Canvas(modifier = modifier.fillMaxSize().clipToBounds()) {
        val width = size.width
        val height = size.height

        particles.forEach { p ->
            val px = p.x * width
            val py = p.y
            if (py in 0f..height) {
                if (p.isSoccerBall) {
                    // Draw a mini soccer ball representation
                    drawCircle(Color.White, radius = p.size, center = Offset(px, py))
                    drawCircle(Color.Black, radius = p.size / 2f, center = Offset(px, py))
                } else {
                    drawRect(p.color, size = androidx.compose.ui.geometry.Size(p.size, p.size), topLeft = Offset(px, py))
                }
            }
        }
    }
}

// ------------------------------------
// TV Style Header Bar
// ------------------------------------
@Composable
fun HeaderBar(
    isRefreshing: Boolean,
    onRefresh: () -> Unit
) {
    var timeString by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            timeString = sdf.format(Date())
            delay(1000)
        }
    }

    Card(
        shape = RoundedCornerShape(0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Logo
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFB91C1C))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "LIVE",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp,
                        fontStyle = FontStyle.Italic
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "חדשות",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Black,
                    fontSize = 22.sp
                )
            }

            // Real clock AND Status Indicator
            Row(verticalAlignment = Alignment.CenterVertically) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = timeString,
                        color = Color(0xFF0F172A),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier
                        .size(38.dp)
                        .background(Color(0xFFEFF6FF), CircleShape)
                        .testTag("refresh_action")
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            color = Color(0xFF2563EB),
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "רענן",
                            tint = Color(0xFF2563EB),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------
// Ticker Marquee Component (Horizontal Scrolling News)
// ------------------------------------
@Composable
fun TickerMarquee(headlines: List<String>) {
    val scrollState = rememberScrollState()

    LaunchedEffect(headlines) {
        while (true) {
            delay(16)
            val currValue = scrollState.value
            val max = scrollState.maxValue
            if (max > 0) {
                val next = currValue + 2
                scrollState.scrollTo(if (next >= max) 0 else next)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFB91C1C)) // Crimson News Ticker Background
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState, enabled = false),
            verticalAlignment = Alignment.CenterVertically
        ) {
            headlines.forEach { headline ->
                Spacer(modifier = Modifier.width(24.dp))
                Box(
                    modifier = Modifier
                        .background(Color.White, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp)
                ) {
                    Text(
                        text = "מבזק",
                        color = Color(0xFFB91C1C),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = headline,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    softWrap = false
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(text = "🚨", fontSize = 14.sp)
            }
        }
    }
}

// ------------------------------------
// Bottom Navigation Bar
// ------------------------------------
@Composable
fun BottomNavBar(
    activeTab: NewsTab,
    isWorldCupActive: Boolean,
    onTabSelected: (NewsTab) -> Unit
) {
    NavigationBar(
        tonalElevation = 8.dp,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // News Tab
        NavigationBarItem(
            selected = activeTab == NewsTab.NEWS,
            onClick = { onTabSelected(NewsTab.NEWS) },
            icon = {
                Icon(
                    imageVector = Icons.Default.Home,
                    contentDescription = "חדשות"
                )
            },
            label = { Text("חדשות LIVE", fontWeight = FontWeight.Bold, fontSize = 11.sp) }
        )

        // World Cup Hot Zone Tab (Displays dynamic Soccer item)
        if (isWorldCupActive) {
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.95f,
                targetValue = 1.1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "scale"
            )

            NavigationBarItem(
                selected = activeTab == NewsTab.WORLD_CUP,
                onClick = { onTabSelected(NewsTab.WORLD_CUP) },
                icon = {
                    Box(modifier = Modifier.graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }) {
                        Text("⚽", fontSize = 21.sp)
                    }
                },
                label = { Text("מונדיאל 2026", fontWeight = FontWeight.Black, fontSize = 11.sp, color = Color(0xFFFF9800)) }
            )
        }

        // Bookmarks Tab
        NavigationBarItem(
            selected = activeTab == NewsTab.SAVED,
            onClick = { onTabSelected(NewsTab.SAVED) },
            icon = {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = "שמורים"
                )
            },
            label = { Text("שמורים", fontWeight = FontWeight.Bold, fontSize = 11.sp) }
        )
    }
}

// ------------------------------------
// Sub-screen 1: News List Tab
// ------------------------------------
@Composable
fun NewsListScreen(
    articles: List<ArticleEntity>,
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    onArticleClicked: (ArticleEntity) -> Unit,
    onBookmarkToggled: (ArticleEntity) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SearchBarField(
            query = searchQuery,
            placeholder = "חפש כותרות או תוכן...",
            onQueryChanged = onSearchQueryChanged
        )

        if (articles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "לא נמצאו כתבות העונות לחיפוש",
                        color = Color.Gray,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(articles, key = { _, item -> item.link }) { index, article ->
                    NewsItemCard(
                        article = article,
                        index = index,
                        onArticleClicked = { onArticleClicked(article) },
                        onBookmarkToggled = { onBookmarkToggled(article) }
                    )
                }
            }
        }
    }
}

// ------------------------------------
// Sub-screen 2: World Cup 2026 Zone
// ------------------------------------
@Composable
fun WorldCupDashboard(
    worldCupArticles: List<ArticleEntity>,
    matches: List<WorldCupMatch>,
    currentGroupFilter: String,
    onGroupChanged: (String) -> Unit,
    onGoalAction: (Int) -> Unit,
    onStartAction: (Int) -> Unit,
    onArticleClicked: (ArticleEntity) -> Unit
) {
    val groupOptions = listOf("כל הבתים", "בית א'", "בית ב'", "בית ג'", "בית ד'", "בית ה'", "בית ו'")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F172A), Color(0xFF14532D)) // Stadium Greenish Deep Blue Gradient
                )
            )
    ) {
        // Magical falling soccer balls and star confetti particles
        WorldCupConfettiEffect()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            // Visual Banner Header
            Card(
                shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2C3E50).copy(alpha = 0.4f)),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "קומפלקס מונדיאל 2026 🏆",
                        color = Color(0xFFFFD700),
                        fontWeight = FontWeight.Black,
                        fontSize = 22.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "עדכוני ספורט ותוצאות בזמן אמת בטורניר הגדול בעולם",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Sub-title for Simulated Matches
            Text(
                text = "📊 תוצאות ומשחקים בשידור חי - סימולטור פעימות",
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Group Horizontal Filter Chips
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(groupOptions) { grp ->
                    val selected = currentGroupFilter == grp
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) Color(0xFF22C55E) else Color.White.copy(alpha = 0.1f)
                        ),
                        border = BorderStroke(1.dp, if (selected) Color.White else Color.White.copy(alpha = 0.2f)),
                        modifier = Modifier.clickable { onGroupChanged(grp) }
                    ) {
                        Text(
                            text = grp,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // List of matches based on filters
            val filteredMatches = if (currentGroupFilter == "כל הבתים") matches else matches.filter { it.group == currentGroupFilter }
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                filteredMatches.forEach { match ->
                    WorldCupMatchCard(
                        match = match,
                        onGoal = { onGoalAction(match.id) },
                        onStart = { onStartAction(match.id) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Real Sports News from Google Search "מונדיאל 2026"
            Text(
                text = "📰 מבזקי ספורט ומונדיאל אמיתיים",
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (worldCupArticles.isEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f))
                ) {
                    Text(
                        text = "טוען מבזקים ספורטיביים אמיתיים...",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    worldCupArticles.take(12).forEach { article ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onArticleClicked(article) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(66.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                ) {
                                    NetworkImage(url = article.imageUrl, modifier = Modifier.fillMaxSize())
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "חדשות ספורט",
                                            color = Color(0xFFFF9800),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp
                                        )
                                        Text(
                                            text = article.pubDate.split(" ").take(4).joinToString(" "),
                                            color = Color.White.copy(alpha = 0.4f),
                                            fontSize = 9.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = article.title,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------
// Sub-screen 3: Bookmarked & Offline Tab
// ------------------------------------
@Composable
fun SavedArticlesScreen(
    articles: List<ArticleEntity>,
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    onClearUnbookmarkedCache: () -> Unit,
    onArticleClicked: (ArticleEntity) -> Unit,
    onBookmarkToggled: (ArticleEntity) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SearchBarField(
            query = searchQuery,
            placeholder = "חפש בכתבות שנשמרו...",
            onQueryChanged = onSearchQueryChanged
        )

        // Cache utilities row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${articles.size} כתבות שמורות לקריאה באופליין 💾",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            
            TextButton(
                onClick = onClearUnbookmarkedCache,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFDC2626)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("נקה זיכרון זמני", fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }

        if (articles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Text(
                        text = "💾",
                        fontSize = 48.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "אין כתבות שמורות",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "סמן כתבות בכוכב כדי שישמרו בהתקן ויהיו זמינות לקריאה מלאה גם ללא חיבור לאינטרנט בכל זמן.",
                        color = Color.Gray,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(articles, key = { _, item -> item.link }) { index, article ->
                    NewsItemCard(
                        article = article,
                        index = index,
                        onArticleClicked = { onArticleClicked(article) },
                        onBookmarkToggled = { onBookmarkToggled(article) }
                    )
                }
            }
        }
    }
}

// ------------------------------------
// Generic Search Bar Composable
// ------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBarField(
    query: String,
    placeholder: String,
    onQueryChanged: (String) -> Unit
) {
    TextField(
        value = query,
        onValueChange = onQueryChanged,
        placeholder = { Text(placeholder, color = Color.Gray, fontSize = 14.sp) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChanged("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "נקה", tint = Color.Gray)
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White,
            disabledContainerColor = Color.White,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
            .testTag("search_field")
    )
}

// ------------------------------------
// Beautiful Headline Item Card
// ------------------------------------
@Composable
fun NewsItemCard(
    article: ArticleEntity,
    index: Int,
    onArticleClicked: () -> Unit,
    onBookmarkToggled: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onArticleClicked() }
            .testTag("news_card_$index")
    ) {
        Column {
            // News Thumbnail with smooth gradient overlay for text readability
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            ) {
                NetworkImage(
                    url = article.imageUrl,
                    modifier = Modifier.fillMaxSize()
                )

                // Bottom source tag & timestamp
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomStart)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                            )
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (article.source == "עכשיו 14") Color(0xFFB91C1C) else Color(0xFF1E3A8A)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = article.source,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }

                    Text(
                        text = if (article.pubDate.contains("+")) {
                            // Trim timezone info if Google format
                            article.pubDate.substring(0, article.pubDate.indexOf("+") - 1).trim()
                        } else article.pubDate,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // News Text Description
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = article.title,
                    color = Color(0xFF0F172A),
                    fontWeight = FontWeight.Black,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = article.description,
                    color = Color(0xFF64748B),
                    fontSize = 12.sp,
                    maxLines = 2,
                    lineHeight = 16.sp,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Action buttons on bottom of card
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onArticleClicked,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("full_article_btn_$index")
                    ) {
                        Text("כתבה מלאה", color = Color.White, fontWeight = FontWeight.Black, fontSize = 11.sp)
                    }

                    IconButton(
                        onClick = onBookmarkToggled,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = if (article.isBookmarked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "שמור כתבה",
                            tint = if (article.isBookmarked) Color(0xFFDC2626) else Color(0xFF94A3B8)
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------
// Interactive World Cup Match Card
// ------------------------------------
@Composable
fun WorldCupMatchCard(
    match: WorldCupMatch,
    onGoal: () -> Unit,
    onStart: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        border = BorderStroke(
            width = 1.dp,
            color = if (match.status in listOf("מחצית ראשונה", "מחצית שנייה")) Color(0xFF22C55E) else Color.White.copy(alpha = 0.1f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Group and Status row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${match.group} • 2026",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Status tag
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (match.status in listOf("מחצית ראשונה", "מחצית שנייה")) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(Color(0xFF22C55E), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "בעיצומו דקה ${match.minute}'",
                            color = Color(0xFF22C55E),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black
                        )
                    } else {
                        Text(
                            text = match.status,
                            color = if (match.status == "סיום") Color(0xFF94A3B8) else Color(0xFFFFD700),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Scoreboard Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Home Team
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = match.homeFlag, fontSize = 28.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = match.homeTeam,
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }

                // Score digits
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GoalScoreBox(score = match.homeScore)
                    Text(text = ":", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    GoalScoreBox(score = match.awayScore)
                }

                // Away Team
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = match.awayFlag, fontSize = 28.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = match.awayTeam,
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Interactive game triggers (Live Simulator Controls)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (match.status == "לא החל") {
                    TextButton(
                        onClick = onStart,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFFD700))
                    ) {
                        Text("התחל משחק ▶️", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                } else if (match.status != "סיום") {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF22C55E).copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                            .clickable { onGoal() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("דמה גול ⚽", color = Color(0xFF22C55E), fontWeight = FontWeight.Black, fontSize = 11.sp)
                    }
                }
            }

            // Events Ticker if present
            if (match.events.isNotEmpty()) {
                HorizontalDivider(
                    color = Color.White.copy(alpha = 0.1f),
                    modifier = Modifier.padding(vertical = 10.dp)
                )
                Text(
                    text = "📣 מהלך המשחק הישיר:",
                    color = Color(0xFFFFD700),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black
                )
                Spacer(modifier = Modifier.height(4.dp))
                match.events.take(3).forEach { ev ->
                    Text(
                        text = ev,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun GoalScoreBox(score: Int) {
    var previousScore by remember { mutableStateOf(score) }
    val animateColor = remember { Animatable(Color(0xFF334155)) }
    
    LaunchedEffect(score) {
        if (score != previousScore) {
            previousScore = score
            // Sparkle animation colors on goals
            animateColor.animateTo(Color(0xFF22C55E), animationSpec = tween(200))
            animateColor.animateTo(Color(0xFF334155), animationSpec = tween(500))
        }
    }

    Box(
        modifier = Modifier
            .size(34.dp, 38.dp)
            .background(animateColor.value, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = score.toString(),
            color = Color.White,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 18.sp
        )
    }
}

// ------------------------------------
// Full Screen Overlay Reading Mode
// ------------------------------------
@Composable
fun ArticleReaderOverlay(
    article: ArticleEntity,
    isLoading: Boolean,
    onClose: () -> Unit,
    onToggleBookmark: () -> Unit,
    onShare: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(enabled = true, onClick = onClose) // tap outside closes
    ) {
        Card(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .align(Alignment.BottomCenter)
                .clickable(enabled = false) {} // prevent clicking behind
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header of overlay
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFFF1F5F9), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "סגור", tint = Color.Black)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(
                            onClick = onShare,
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color(0xFFEFF6FF), CircleShape)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "שתף", tint = Color(0xFF2563EB))
                        }

                        IconButton(
                            onClick = onToggleBookmark,
                            modifier = Modifier
                                .size(36.dp)
                                .background(if (article.isBookmarked) Color(0xFFFEE2E2) else Color(0xFFF1F5F9), CircleShape)
                        ) {
                            Icon(
                                imageVector = if (article.isBookmarked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "שמור",
                                tint = if (article.isBookmarked) Color(0xFFDC2626) else Color.Black
                            )
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFFE2E8F0))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Title and Metadata
                    Column(modifier = Modifier.padding(18.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (article.source == "עכשיו 14") Color(0xFFB91C1C) else Color(0xFF1E3A8A))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = article.source,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = article.title,
                            color = Color(0xFF0F172A),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 20.sp,
                            lineHeight = 26.sp
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "פורסם: ${article.pubDate}",
                            color = Color.Gray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Banner image
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    ) {
                        NetworkImage(url = article.imageUrl, modifier = Modifier.fillMaxSize())
                    }

                    // Article text body
                    Column(modifier = Modifier.padding(18.dp)) {
                        if (isLoading) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(color = Color(0xFFB91C1C))
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "מחלץ כתבה מלאה מ-${article.source}...",
                                    color = Color.Gray,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            if (!article.fullContent.isNullOrBlank()) {
                                Text(
                                    text = article.fullContent,
                                    color = Color(0xFF1E293B),
                                    fontSize = 14.sp,
                                    lineHeight = 22.sp,
                                    fontWeight = FontWeight.Normal,
                                    textAlign = TextAlign.Justify
                                )
                            } else {
                                // Fallback description with offline disclaimer
                                Card(
                                    shape = RoundedCornerShape(8.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                                    border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 16.dp)
                                ) {
                                    Text(
                                        text = "מצב קריאה מהירה: מוצג תקציר כגיבוי. לקריאת הכתבה הדקורטיבית המלאה, יש להסתכל באתר המקורי.",
                                        color = Color(0xFF1E40AF),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }

                                Text(
                                    text = article.description,
                                    color = Color(0xFF0F172A),
                                    fontSize = 14.sp,
                                    lineHeight = 22.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            Spacer(modifier = Modifier.height(32.dp))

                            // Anchor details back to webpage
                            val localUriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                            Button(
                                onClick = {
                                    try {
                                        localUriHandler.openUri(article.link)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                Text(
                                    text = "קרא כתבה מלאה באתר ${article.source}",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }
}
