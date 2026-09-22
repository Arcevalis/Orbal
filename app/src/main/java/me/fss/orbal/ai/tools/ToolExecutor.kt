package me.fss.orbal.ai.tools

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.app.ActivityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import android.util.Log

@Singleton
class ToolExecutor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val retrievalService: me.fss.orbal.data.rag.RetrievalService,
    private val settingsRepository: me.fss.orbal.data.repository.SettingsRepository,
) {
    // Optional override for tests / legacy setter
    var knowledgeSearch: (suspend (String) -> String)? = null

    private val webClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val readClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    suspend fun execute(call: ToolCall): ToolResult {
        val start = System.currentTimeMillis()
        return try {
            val content = dispatch(call)
            val raw = ToolResult(toolCallId = call.id, name = call.name, content = content, durationMs = System.currentTimeMillis() - start)
            normalizeToolResult(call, raw)
        } catch (e: Exception) {
            Log.e("ToolExecutor", "Error executing ${call.name}", e)
            toolErrorResult(call, e, start)
        }
    }

    private suspend fun dispatch(call: ToolCall): String = withContext(Dispatchers.IO) {
        when (call.name) {
            "web_search" -> {
                val q = requireString(call, "query")
                handleWebSearch(q)
            }
            "calculator" -> {
                val expr = call.arguments["expression"] as? String ?: throw IllegalArgumentException("Missing required parameter: expression")
                handleCalculator(expr)
            }
            "get_current_datetime" -> {
                val tz = call.arguments["timezone"] as? String
                handleGetDatetime(tz)
            }
            "get_device_info" -> {
                val t = (call.arguments["info_type"] as? String) ?: "all"
                handleGetDeviceInfo(t)
            }
            "search_knowledge_base" -> {
                val q = requireString(call, "query")
                val fn = knowledgeSearch
                if (fn != null) {
                    fn(q)
                } else {
                    // Use RetrievalService directly — respects tools settings contextLength
                    val results = retrievalService.search(query = q, topK = 5)
                    if (results.isEmpty()) "No matching documents found for \"$q\"."
                    else retrievalService.formatForPrompt(results)
                }
            }
            "read_url" -> {
                val url = requireString(call, "url")
                handleReadUrl(url)
            }
            // alias fetch_url -> read_url
            "fetch_url" -> {
                val url = requireString(call, "url")
                handleReadUrl(url)
            }
            else -> throw IllegalArgumentException("Unknown tool: ${call.name}")
        }
    }

    private fun requireString(call: ToolCall, param: String): String {
        val v = call.arguments[param] as? String
        if (v != null && v.trim().isNotEmpty()) return v.trim()
        throw IllegalArgumentException("Missing required parameter: $param")
    }

    // ——— Handlers ———

    private fun handleCalculator(expression: String): String {
        val sanitized = expression.replace("\\s".toRegex(), "")
        if (!Regex("""^[0-9+\-*/().,%^]+$""").matches(sanitized)) {
            throw IllegalArgumentException("Invalid expression: only numbers and basic operators (+, -, *, /, ^, %, parentheses) are allowed")
        }
        val result = evaluateExpression(sanitized)
        if (!result.isFinite()) throw IllegalArgumentException("Expression did not evaluate to a finite number")
        // Strip .0 for ints
        val display = if (result % 1.0 == 0.0) result.toLong().toString() else result.toString()
        return "$expression = $display"
    }

    /** Recursive descent — mirrors OGAM evaluateExpression */
    private fun evaluateExpression(expr: String): Double {
        val str = expr.replace("\\s".toRegex(), "")
        val parser = ExprParser(str)
        val r = parser.parseExpr()
        if (parser.pos < str.length) throw IllegalArgumentException("Unexpected character at ${parser.pos}")
        return r
    }

    private class ExprParser(private val str: String) {
        var pos: Int = 0
        fun parseExpr(): Double {
            var left = parseTerm()
            while (pos < str.length && (str[pos] == '+' || str[pos] == '-')) {
                val op = str[pos++]
                val right = parseTerm()
                left = if (op == '+') left + right else left - right
            }
            return left
        }
        fun parseTerm(): Double {
            var left = parsePower()
            while (pos < str.length && (str[pos] == '*' || str[pos] == '/' || str[pos] == '%')) {
                val op = str[pos++]
                val right = parsePower()
                left = when (op) {
                    '*' -> left * right
                    '/' -> left / right
                    else -> left % right
                }
            }
            return left
        }
        fun parsePower(): Double {
            var base = parseUnary()
            if (pos < str.length && str[pos] == '^') {
                pos++
                val exp = parsePower()
                base = Math.pow(base, exp)
            }
            return base
        }
        fun parseUnary(): Double {
            if (pos < str.length && str[pos] == '-') { pos++; return -parseAtom() }
            if (pos < str.length && str[pos] == '+') { pos++; return parseAtom() }
            return parseAtom()
        }
        fun parseAtom(): Double {
            if (pos < str.length && str[pos] == '(') {
                pos++
                val v = parseExpr()
                if (pos >= str.length || str[pos] != ')') throw IllegalArgumentException("Mismatched parentheses")
                pos++
                return v
            }
            val start = pos
            while (pos < str.length && (str[pos].isDigit() || str[pos] == '.')) pos++
            if (pos == start) throw IllegalArgumentException("Unexpected character at $pos: ${str.getOrNull(pos)}")
            return str.substring(start, pos).toDouble()
        }
    }

    private fun handleGetDatetime(timezone: String?): String {
        val now = ZonedDateTime.now()
        return try {
            val zone = if (!timezone.isNullOrBlank()) ZoneId.of(timezone) else ZoneId.systemDefault()
            val zoned = now.withZoneSameInstant(zone)
            val fmt = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy HH:mm:ss z")
            val formatted = zoned.format(fmt)
            val iso = zoned.toInstant().toString()
            val unix = zoned.toEpochSecond()
            "Current date and time: $formatted\nISO 8601: $iso\nUnix timestamp: $unix\nTimezone: $zone"
        } catch (e: Exception) {
            val fallback = now.format(DateTimeFormatter.RFC_1123_DATE_TIME)
            "Current date and time: $fallback\nNote: requested timezone \"$timezone\" was invalid, showing device local time. Error: ${e.message}"
        }
    }

    private fun handleGetDeviceInfo(infoType: String): String {
        val type = infoType.lowercase()
        val parts = mutableListOf<String>()

        fun safe(label: String, block: () -> String): String = try { block() } catch (e: Exception) { "$label: unavailable (${e.message})" }

        if (type == "all" || type == "memory") {
            parts += safe("Memory") {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                val total = mi.totalMem
                val avail = mi.availMem
                val used = total - avail
                "Memory:\n  Total: ${formatBytes(total)}\n  Used: ${formatBytes(used)}\n  Available: ${formatBytes(avail)}\n  Low: ${mi.lowMemory} threshold ${formatBytes(mi.threshold)}"
            }
        }
        if (type == "all" || type == "storage") {
            parts += safe("Storage") {
                val stat = StatFs(Environment.getDataDirectory().path)
                val total = stat.blockCountLong * stat.blockSizeLong
                val free = stat.availableBlocksLong * stat.blockSizeLong
                "Storage (data):\n  Total: ${formatBytes(total)}\n  Free: ${formatBytes(free)}\n  Used: ${formatBytes(total - free)}"
            }
        }
        if (type == "all" || type == "battery") {
            parts += safe("Battery") {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    if (pct >= 0) "$pct%" else "unknown"
                } else "unknown"
                val charging = try {
                    val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                    val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                    if (isCharging) " (charging)" else ""
                } catch (_: Exception) { "" }
                "Battery: $level$charging"
            }
        }
        if (type == "all") {
            parts += "Device: ${Build.MANUFACTURER} ${Build.MODEL}"
            parts += "OS: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) ABI ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}"
        }
        return parts.joinToString("\n\n")
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format("%.2f GB", gb)
    }

    private fun handleWebSearch(query: String): String {
        Log.i("ToolExecutor", "web_search query=\"$query\"")
        // Try Brave first — with snippet enrichment + JSON fallback
        val brave = try { fetchBrave(query) } catch (e: Exception) {
            Log.w("ToolExecutor", "Brave search failed: ${e.message}")
            null
        }
        if (brave != null && brave.isNotEmpty()) {
            val hasGoodSnippet = brave.any { it.snippet.isNotBlank() && it.snippet != "(no snippet)" && it.snippet.length > 20 }
            if (hasGoodSnippet || brave.size >= 3) {
                Log.i("ToolExecutor", "Brave returned ${brave.size} results, goodSnippet=$hasGoodSnippet")
                return formatSearchResults(brave.take(5))
            }
            // Brave returned poor snippets — try to enrich via JSON fallback before giving up
            if (brave.size in 1..2) {
                val jsonEnriched = try { enrichWithJsonFallback(brave, lastBraveHtml) } catch (_: Exception) { brave }
                if (jsonEnriched.any { it.snippet.length > 30 }) {
                    Log.i("ToolExecutor", "JSON enrichment improved snippets")
                    return formatSearchResults(jsonEnriched.take(5))
                }
            }
        }
        // Fallback: DuckDuckGo Lite HTML — more parse-friendly, no JS
        Log.i("ToolExecutor", "Brave insufficient, trying DDG fallback for \"$query\"")
        val ddg = try { fetchDdgLite(query) } catch (e: Exception) {
            Log.w("ToolExecutor", "DDG fallback failed: ${e.message}")
            emptyList()
        }
        if (ddg.isNotEmpty()) {
            Log.i("ToolExecutor", "DDG returned ${ddg.size} results")
            return formatSearchResults(ddg.take(5))
        }
        // Last resort: if Brave had at least 1 title, return it even without snippet
        if (brave != null && brave.isNotEmpty()) {
            Log.i("ToolExecutor", "Returning Brave titles without snippets")
            return formatSearchResults(brave.take(5))
        }
        return "No results found for \"$query\"."
    }

    private var lastBraveHtml: String = ""

    private fun fetchBrave(query: String): List<SearchResult> {
        val url = "https://search.brave.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&source=web"
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cache-Control", "no-cache")
            .build()
        webClient.newCall(req).execute().use { resp ->
            lastBraveHtml = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw java.io.IOException("Search HTTP ${resp.code} ${resp.message}")
            Log.i("ToolExecutor", "Brave HTML ${lastBraveHtml.length} chars, HTTP ${resp.code}")
            val results = parseBraveResults(lastBraveHtml)
            // Try JSON fallback enrichment if snippets empty
            return if (results.any { it.snippet == "(no snippet)" || it.snippet.isBlank() }) {
                enrichWithJsonFallback(results, lastBraveHtml)
            } else results
        }
    }

    private fun enrichWithJsonFallback(results: List<SearchResult>, html: String): List<SearchResult> {
        if (results.isEmpty()) return results
        // Brave embeds JSON with "description":"...." for each result — extract as snippet candidates
        val descPattern = Regex("""\"description\"\s*:\s*\"((?:\\u[0-9a-fA-F]{4}|\\.|[^\"])*)\"""")
        val candidates = mutableListOf<String>()
        for (m in descPattern.findAll(html)) {
            var raw = m.groupValues[1]
            // Unescape JSON string
            raw = raw.replace("\\n", " ").replace("\\\"", "\"").replace("\\/", "/")
            // Decode unicode \u003c etc
            raw = raw.replace(Regex("""\\u([0-9a-fA-F]{4})""")) { runCatching { String(Character.toChars(it.groupValues[1].toInt(16))) }.getOrDefault(it.value) }
            raw = decodeHtmlEntities(stripHtmlTags(raw).trim())
            // Keep only plausible snippet lengths and skip Brave boilerplate
            if (raw.length in 40..600 && !raw.contains("Brave Search", ignoreCase = true) && !raw.contains("profile", ignoreCase = true)) {
                candidates += raw
            }
            if (candidates.size >= 10) break
        }
        if (candidates.isEmpty()) return results
        Log.i("ToolExecutor", "JSON fallback found ${candidates.size} description candidates")
        // Fill empty snippets in order
        return results.mapIndexed { idx, r ->
            if ((r.snippet == "(no snippet)" || r.snippet.isBlank()) && idx < candidates.size) {
                r.copy(snippet = candidates[idx].take(400))
            } else r
        }
    }

    private fun fetchDdgLite(query: String): List<SearchResult> {
        // Lite is smallest and most parse-friendly; html also works
        val url = "https://lite.duckduckgo.com/lite/?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .header("Accept", "text/html")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        webClient.newCall(req).execute().use { resp ->
            val html = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw java.io.IOException("DDG HTTP ${resp.code}")
            Log.i("ToolExecutor", "DDG HTML ${html.length} chars")
            return parseDdgResults(html)
        }
    }

    private fun parseDdgResults(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        // Lite format: <a rel="nofollow" href="https://...">Title</a> ... <td class="result-snippet">Snippet</td>
        // More generic: capture hrefs with snippets
        val blockPat = Regex("""<a[^>]*href="(https?://[^"]+)"[^>]*>([^<]{8,150})</a>[\s\S]{0,600}?class="[^"]*snippet[^"]*"[^>]*>([\s\S]*?)</td>""", RegexOption.IGNORE_CASE)
        for (m in blockPat.findAll(html)) {
            if (results.size >= 5) break
            val url = decodeHtmlEntities(m.groupValues[1].trim())
            val title = decodeHtmlEntities(stripHtmlTags(m.groupValues[2]).trim())
            val snippet = decodeHtmlEntities(stripHtmlTags(m.groupValues[3]).trim()).take(400)
            if (url.contains("duckduckgo.com", ignoreCase = true)) continue
            results += SearchResult(title = title.ifEmpty { "(no title)" }, snippet = snippet.ifEmpty { "(no snippet)" }, url = url)
        }
        if (results.isEmpty()) {
            // Fallback: just links
            val linkPat = Regex("""<a[^>]*href="(https?://(?!duckduckgo)[^"]+)"[^>]*>([^<]{10,120})</a>""")
            for (m in linkPat.findAll(html)) {
                if (results.size >= 5) break
                val url = decodeHtmlEntities(m.groupValues[1].trim())
                val title = decodeHtmlEntities(m.groupValues[2].trim())
                if (title.contains("DuckDuckGo", ignoreCase = true)) continue
                if (url.contains("duckduckgo.com")) continue
                results += SearchResult(title = title, snippet = "(no snippet)", url = url)
            }
        }
        return results
    }

    private fun formatSearchResults(results: List<SearchResult>): String {
        return results.mapIndexed { i, r ->
            val heading = if (r.url != null) "[${r.title}](${r.url})" else r.title
            val snippetLine = if (r.snippet.isNotBlank() && r.snippet != "(no snippet)") "\n   ${r.snippet}" else ""
            "${i + 1}. $heading$snippetLine"
        }.joinToString("\n\n")
    }

    private data class SearchResult(val title: String, val snippet: String, val url: String?)

    private fun parseBraveResults(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val blocks = html.split("class=\"result-wrapper").drop(1)
        for (block in blocks) {
            if (results.size >= 5) break
            parseResultBlock(block)?.let { results += it }
        }
        if (results.isEmpty()) {
            val linkPat = Regex("""<a[^>]*href="(https?://(?!search\.brave)[^"]*)"[^>]*>([^<]{10,})</a>""")
            for (m in linkPat.findAll(html)) {
                if (results.size >= 5) break
                val title = decodeHtmlEntities(m.groupValues[2].trim())
                if (!title.contains("Brave", ignoreCase = true)) {
                    results += SearchResult(title = title, snippet = "", url = m.groupValues[1])
                }
            }
        }
        return results
    }

    private fun parseResultBlock(block: String): SearchResult? {
        val url = Regex("""<a[^>]*href="(https?://[^"]+)"""").find(block)?.groupValues?.getOrNull(1)?.let { decodeHtmlEntities(it) } ?: ""
        val title = (Regex("""class="[^"]*title[^"]*"[^>]*>([^<]+)<""").find(block)?.groupValues?.getOrNull(1)
            ?: Regex("""<a[^>]*href="https?://[^"]*"[^>]*>\s*<span[^>]*>([^<]+)""").find(block)?.groupValues?.getOrNull(1)
            ?: Regex("""<a[^>]*href="https?://[^"]*"[^>]*>([^<]{8,150})</a>""").find(block)?.groupValues?.getOrNull(1)
        )?.let { decodeHtmlEntities(it.trim()) } ?: ""
        // Try multiple snippet containers - Brave uses p/span/div with snippet class
        val snippetRaw = (Regex("""class="snippet[^"]*"[^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE).find(block)?.groupValues?.getOrNull(1)
            ?: Regex("""class="snippet[^"]*"[^>]*>([\s\S]*?)</span>""", RegexOption.IGNORE_CASE).find(block)?.groupValues?.getOrNull(1)
            ?: Regex("""class="snippet[^"]*"[^>]*>([\s\S]*?)</div>""", RegexOption.IGNORE_CASE).find(block)?.groupValues?.getOrNull(1)
            ?: Regex("""class="[^"]*snippet[^"]*"[^>]*>([\s\S]{20,600}?)</""", RegexOption.IGNORE_CASE).find(block)?.groupValues?.getOrNull(1)
        ) ?: ""
        val snippet = decodeHtmlEntities(stripHtmlTags(snippetRaw).trim()).take(500)
        if (title.isEmpty() && snippet.isEmpty()) return null
        return SearchResult(title = title.ifEmpty { "(no title)" }, snippet = snippet.ifEmpty { "(no snippet)" }, url = url.ifEmpty { null })
    }

    private fun stripHtmlTags(html: String): String {
        val sb = StringBuilder()
        var inTag = false
        for (ch in html) {
            when (ch) {
                '<' -> inTag = true
                '>' -> inTag = false
                else -> if (!inTag) sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun decodeHtmlEntities(text: String): String {
        return text.replace(Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+);""")) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") -> runCatching { String(Character.toChars(body.substring(2).toInt(16))) }.getOrDefault(m.value)
                body.startsWith("#") -> runCatching { String(Character.toChars(body.substring(1).toInt())) }.getOrDefault(m.value)
                else -> when (body) {
                    "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "; else -> m.value
                }
            }
        }
    }

    // ——— read_url ———

    private fun isPrivateUrl(url: String): Boolean {
        val m = Regex("""^https?://([^/:]+)""", RegexOption.IGNORE_CASE).find(url) ?: return false
        val h = m.groupValues[1].lowercase()
        if (h == "localhost" || h == "[::1]" || h == "metadata.google.internal") return true
        return Regex("""^(127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|0\.|169\.254\.)""").containsMatchIn(h)
    }

    private fun handleReadUrl(rawUrl: String): String {
        var url = rawUrl.trim()
        while (url.isNotEmpty() && "\"'<> ".contains(url.first())) url = url.drop(1)
        while (url.isNotEmpty() && "\"'<> ".contains(url.last())) url = url.dropLast(1)
        if (!Regex("""^https?://""", RegexOption.IGNORE_CASE).containsMatchIn(url)) throw IllegalArgumentException("Invalid URL: must start with http:// or https://")
        if (isPrivateUrl(url)) throw IllegalArgumentException("Blocked: cannot fetch private/local network URLs")
        // Guard against huge downloads via content-length header check later — OkHttp handles chunked fine but we cap body
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,*/*;q=0.5")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        Log.i("ToolExecutor", "read_url fetching $url")
        readClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("Fetch HTTP ${resp.code} ${resp.message}")
            val contentType = resp.header("Content-Type") ?: ""
            val rawBody = resp.body?.string() ?: ""
            Log.i("ToolExecutor", "read_url ${resp.code} ${contentType} ${rawBody.length} chars")
            // Content-type guard: plain text / json / xml can be returned without HTML markdown conversion
            if (contentType.contains("text/plain", ignoreCase = true)) {
                val text = rawBody.trim()
                val MAX = 4000
                return if (text.length <= MAX) text else text.take(MAX) + "\n\n[Truncated to $MAX chars of ${text.length}]"
            }
            if (contentType.contains("application/json", ignoreCase = true) || contentType.contains("application/xml", ignoreCase = true)) {
                val text = rawBody.trim().take(6000)
                return text
            }
            // Size guard — if HTML > 2MB, truncate early before parsing (prevents OOM)
            val bodyForParse = if (rawBody.length > 2_000_000) {
                Log.w("ToolExecutor", "read_url body huge ${rawBody.length}, truncating to 2M")
                rawBody.take(2_000_000)
            } else rawBody
            val markdown = htmlToMarkdown(bodyForParse)
            val MAX = 4000
            // Also log preview for debugging
            Log.i("ToolExecutor", "read_url markdown ${markdown.length} chars, preview: ${markdown.take(200).replace("\n"," ")}")
            return if (markdown.length <= MAX) markdown else markdown.take(MAX) + "\n\n[Truncated to $MAX chars of ${markdown.length}]"
        }
    }

    private fun htmlToMarkdown(html: String): String {
        var s = html
        // Prefer semantic main content if present — reduces nav boilerplate
        val preferred = runCatching {
            Regex("""<article[^>]*>([\s\S]*?)</article>""", RegexOption.IGNORE_CASE).find(s)?.groupValues?.getOrNull(1)
                ?: Regex("""<main[^>]*>([\s\S]*?)</main>""", RegexOption.IGNORE_CASE).find(s)?.groupValues?.getOrNull(1)
                ?: Regex("""<div[^>]*id\s*=\s*["']content["'][^>]*>([\s\S]*?)</div>\s*</div>""", RegexOption.IGNORE_CASE).find(s)?.groupValues?.getOrNull(1)
                ?: Regex("""<body[^>]*>([\s\S]*?)</body>""", RegexOption.IGNORE_CASE).find(s)?.groupValues?.getOrNull(1)
        }.getOrNull()
        if (preferred != null && preferred.length > 800 && preferred.length > s.length / 3) {
            s = preferred
        }
        // Remove boilerplate tags and their content
        val blockTags = listOf("script","style","nav","header","footer","aside","noscript","iframe","form","button","figure","picture","svg","canvas","video","audio","img","noscript")
        for (tag in blockTags) {
            s = s.replace(Regex("""<$tag[\s\S]*?</$tag>""", RegexOption.IGNORE_CASE), " ")
            s = s.replace(Regex("""<$tag[^>]*/?>""", RegexOption.IGNORE_CASE), " ")
        }
        // Keep links as markdown: [text](url) — useful for citations
        s = s.replace(Regex("""<a[^>]*href\s*=\s*["']([^"']+)["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)) { m ->
            val href = m.groupValues[1].trim()
            val text = stripHtmlTags(m.groupValues[2]).trim().replace(Regex("""\s+"""), " ")
            if (text.isBlank() || href.isBlank() || href.startsWith("#") || href.startsWith("javascript:")) text else "[$text]($href)"
        }
        // Headings
        s = s.replace(Regex("""<h1[^>]*>([\s\S]*?)</h1>""", RegexOption.IGNORE_CASE)) { "\n\n## ${stripHtmlTags(it.groupValues[1]).trim()}\n" }
        s = s.replace(Regex("""<h2[^>]*>([\s\S]*?)</h2>""", RegexOption.IGNORE_CASE)) { "\n\n## ${stripHtmlTags(it.groupValues[1]).trim()}\n" }
        s = s.replace(Regex("""<h3[^>]*>([\s\S]*?)</h3>""", RegexOption.IGNORE_CASE)) { "\n\n### ${stripHtmlTags(it.groupValues[1]).trim()}\n" }
        s = s.replace(Regex("""<h[4-6][^>]*>([\s\S]*?)</h[4-6]>""", RegexOption.IGNORE_CASE)) { "\n\n### ${stripHtmlTags(it.groupValues[1]).trim()}\n" }
        // Preserve code/pre as inline code
        s = s.replace(Regex("""<pre[^>]*>([\s\S]*?)</pre>""", RegexOption.IGNORE_CASE)) { "\n```\n${stripHtmlTags(it.groupValues[1]).trim()}\n```\n" }
        s = s.replace(Regex("""<code[^>]*>([\s\S]*?)</code>""", RegexOption.IGNORE_CASE)) { "`${stripHtmlTags(it.groupValues[1]).trim()}`" }
        s = s.replace(Regex("""<p[^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)) { "\n\n${stripHtmlTags(it.groupValues[1]).trim()}" }
        s = s.replace(Regex("""<li[^>]*>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE)) { "\n- ${stripHtmlTags(it.groupValues[1]).trim()}" }
        s = s.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("""<tr[^>]*>""", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("""<blockquote[^>]*>([\s\S]*?)</blockquote>""", RegexOption.IGNORE_CASE)) { "\n> ${stripHtmlTags(it.groupValues[1]).trim()}\n" }
        // Strip remaining tags
        s = stripHtmlTags(s)
        s = decodeHtmlEntities(s)
        // Normalize whitespace: collapse horizontal spaces, then vertical
        s = s.replace(Regex("""[ \t\x0B\f\r]+"""), " ")
        s = s.replace(Regex(""" *\n *"""), "\n")
        s = s.replace(Regex("""\n{3,}"""), "\n\n")
        // Trim lines with only spaces
        s = s.lines().joinToString("\n") { it.trimEnd() }.trim()
        if (s.isEmpty()) s = "(no readable content)"
        return s
    }
}
