package com.example.network

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

data class RssItem(
    val title: String,
    val link: String,
    val description: String,
    val pubDate: String,
    val source: String,
    val imageUrl: String?
)

object RssFeedFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    fun fetchAndParseFeed(url: String, sourceName: String): List<RssItem> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("RssFeedFetcher", "Error fetching from $sourceName: Code ${response.code}")
                    return emptyList()
                }
                val bodyString = response.body?.string() ?: return emptyList()
                Log.d("RssFeedFetcher", "Feed fetched successfully from $sourceName, length: ${bodyString.length}")
                return parseXml(bodyString, sourceName)
            }
        } catch (e: Exception) {
            Log.e("RssFeedFetcher", "Error fetching feed: ${e.message}", e)
            return emptyList()
        }
    }

    private fun parseXml(xmlString: String, sourceName: String): List<RssItem> {
        val items = mutableListOf<RssItem>()
        try {
            // Trim and replace any common BOM or weird signs
            var sanitized = xmlString.trim()
            if (sanitized.startsWith("<?xml") || sanitized.contains("<rss") || sanitized.contains("<feed")) {
                // Remove potential characters before <?xml
                val idx = sanitized.indexOf("<?xml")
                if (idx > 0) {
                    sanitized = sanitized.substring(idx)
                }
            }
            
            val factory = DocumentBuilderFactory.newInstance()
            // Turn off namespaces validation if it is broken
            factory.isNamespaceAware = false
            
            val builder = factory.newDocumentBuilder()
            val input = ByteArrayInputStream(sanitized.toByteArray(Charsets.UTF_8))
            val doc = builder.parse(input)
            
            val nodeList = doc.getElementsByTagName("item")
            for (i in 0 until nodeList.length) {
                val node = nodeList.item(i)
                if (node is Element) {
                    val title = node.getElementsByTagName("title").item(0)?.textContent ?: ""
                    val link = node.getElementsByTagName("link").item(0)?.textContent ?: ""
                    val pubDate = node.getElementsByTagName("pubDate").item(0)?.textContent ?: ""
                    val description = node.getElementsByTagName("description").item(0)?.textContent ?: ""

                    // Extract image
                    var imageUrl: String? = null
                    
                    val enclosureList = node.getElementsByTagName("enclosure")
                    if (enclosureList.length > 0) {
                        val enclosure = enclosureList.item(0) as Element
                        imageUrl = enclosure.getAttribute("url")
                    }

                    if (imageUrl == null) {
                        val mediaList = node.getElementsByTagName("media:content")
                        if (mediaList.length > 0) {
                            imageUrl = (mediaList.item(0) as Element).getAttribute("url")
                        }
                    }

                    if (imageUrl == null) {
                        val contentList = node.getElementsByTagName("content:encoded")
                        if (contentList.length > 0) {
                            imageUrl = extractImageUrlFromHtml(contentList.item(0).textContent)
                        }
                    }

                    if (imageUrl == null && description.isNotEmpty()) {
                        imageUrl = extractImageUrlFromHtml(description)
                    }

                    // For standard Google News RSS, links can sometimes be double redirect.
                    // But standard link works perfectly if loaded in WebView or stripped.

                    items.add(
                        RssItem(
                            title = title.trim(),
                            link = link.trim(),
                            description = stripHtml(description),
                            pubDate = pubDate.trim(),
                            source = sourceName,
                            imageUrl = imageUrl
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("RssFeedFetcher", "DOM parsing failed. Trying regex fallback for $sourceName.", e)
            try {
                return parseRssViaRegex(xmlString, sourceName)
            } catch (ex: Exception) {
                Log.e("RssFeedFetcher", "Regex parsing also failed for $sourceName.", ex)
            }
        }
        return items
    }

    private fun parseRssViaRegex(xmlString: String, sourceName: String): List<RssItem> {
        val items = mutableListOf<RssItem>()
        // Simple regex parser that parses <item> tags
        val itemRegex = "<item>(.*?)</item>".toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val titleRegex = "<title>(.*?)</title>".toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val linkRegex = "<link>(.*?)</link>".toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val pubDateRegex = "<pubDate>(.*?)</pubDate>".toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val descRegex = "<description>(.*?)</description>".toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val enclosureRegex = "<enclosure[^>]+url\\s*=\\s*['\"]([^'\"]+)['\"]".toRegex(RegexOption.IGNORE_CASE)

        val matches = itemRegex.findAll(xmlString)
        for (match in matches) {
            val itemContent = match.groups[1]?.value ?: continue
            val title = titleRegex.find(itemContent)?.groups?.get(1)?.value?.let { cleanCdata(it) } ?: ""
            val link = linkRegex.find(itemContent)?.groups?.get(1)?.value?.let { cleanCdata(it) } ?: ""
            val pubDate = pubDateRegex.find(itemContent)?.groups?.get(1)?.value?.let { cleanCdata(it) } ?: ""
            val desc = descRegex.find(itemContent)?.groups?.get(1)?.value?.let { cleanCdata(it) } ?: ""
            
            var imageUrl: String? = enclosureRegex.find(itemContent)?.groups?.get(1)?.value
            if (imageUrl == null) {
                imageUrl = extractImageUrlFromHtml(desc)
            }

            items.add(
                RssItem(
                    title = title.trim(),
                    link = link.trim(),
                    description = stripHtml(desc),
                    pubDate = pubDate.trim(),
                    source = sourceName,
                    imageUrl = imageUrl
                )
            )
        }
        return items
    }

    private fun cleanCdata(content: String): String {
        return content.replace("<![CDATA[", "").replace("]]>", "").trim()
    }

    private fun extractImageUrlFromHtml(html: String): String? {
        val regex = "<img[^>]+src\\s*=\\s*['\"]([^'\"]+)['\"]".toRegex(RegexOption.IGNORE_CASE)
        val match = regex.find(html)
        var src = match?.groups?.get(1)?.value
        if (src != null) {
            src = cleanCdata(src)
            if (src.startsWith("//")) {
                src = "https:$src"
            }
        }
        return src
    }

    fun stripHtml(html: String): String {
        var clean = cleanCdata(html)
        clean = clean.replace("<style[^>]*>.*?</style>".toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
        clean = clean.replace("<script[^>]*>.*?</script>".toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
        clean = clean.replace("<[^>]*>".toRegex(), " ")
        clean = clean.replace("&nbsp;", " ")
        clean = clean.replace("&quot;", "\"")
        clean = clean.replace("&amp;", "&")
        clean = clean.replace("&lt;", "<")
        clean = clean.replace("&gt;", ">")
        clean = clean.replace("&apos;", "'")
        return clean.trim().replace("\\s+".toRegex(), " ")
    }

    fun fetchFullArticleText(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body?.string() ?: return null
                
                // Seek standard content blocks first
                var contentText = ""
                
                // Let's find tags like <p>...</p> which are likely the actual text paragraphs
                val pRegex = "<p>([^<]+)</p>".toRegex(RegexOption.IGNORE_CASE)
                val paragraphs = pRegex.findAll(html)
                    .map { it.groups[1]?.value ?: "" }
                    .map { cleanCdata(it) }
                    .filter { it.trim().length > 20 } // skip short footer/script items
                    .map { stripHtml(it) }
                    .toList()
                
                if (paragraphs.isNotEmpty()) {
                    contentText = paragraphs.joinToString("\n\n")
                    if (contentText.length > 200) {
                        return contentText
                    }
                }
                
                // Fallback: Body parsing
                val startBody = html.indexOf("<body", ignoreCase = true)
                val endBody = html.indexOf("</body>", ignoreCase = true)
                if (startBody != -1 && endBody != -1 && endBody > startBody) {
                    val bodyHtml = html.substring(startBody, endBody)
                    return stripHtml(bodyHtml)
                }
                
                return stripHtml(html)
            }
        } catch (e: Exception) {
            Log.e("RssFeedFetcher", "Error scraping web page $url: ${e.message}", e)
            return null
        }
    }
}
