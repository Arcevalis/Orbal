package me.fss.orbal.data.rag

data class RagChunk(
    val content: String,
    val position: Int,
)

/**
 * Simple sliding window chunker — Kotlin-native.
 * No external deps, ~800 chars with 100 overlap, respects paragraph boundaries when possible.
 */
fun chunkDocument(text: String, chunkSize: Int = 800, overlap: Int = 100): List<RagChunk> {
    val cleaned = text.replace("\r\n", "\n").trim()
    if (cleaned.isEmpty()) return emptyList()
    if (cleaned.length <= chunkSize) return listOf(RagChunk(cleaned, 0))

    val chunks = mutableListOf<RagChunk>()
    var start = 0
    var pos = 0
    while (start < cleaned.length) {
        var end = (start + chunkSize).coerceAtMost(cleaned.length)
        // Prefer to break at paragraph or sentence boundary if within 100 chars of end
        if (end < cleaned.length) {
            val window = cleaned.substring((end - 120).coerceAtLeast(start), end)
            val paraIdx = window.lastIndexOf("\n\n")
            val sentIdx = window.lastIndexOf(". ")
            val breakOff = when {
                paraIdx != -1 -> paraIdx + 2
                sentIdx != -1 -> sentIdx + 2
                else -> {
                    val spaceIdx = window.lastIndexOf(' ')
                    if (spaceIdx != -1) spaceIdx + 1 else 0
                }
            }
            if (breakOff > 30) {
                end = (end - 120 + breakOff).coerceAtMost(cleaned.length)
            }
        }
        val piece = cleaned.substring(start, end).trim()
        if (piece.isNotEmpty()) chunks += RagChunk(piece, pos++)
        if (end >= cleaned.length) break
        start = (end - overlap).coerceAtLeast(start + 1)
    }
    return chunks
}
