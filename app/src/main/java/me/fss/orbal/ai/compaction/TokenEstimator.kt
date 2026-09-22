package me.fss.orbal.ai.compaction

import me.fss.orbal.data.repository.MessageWithAttachments

object TokenEstimator {
    const val CHARS_PER_TOKEN = 4
    const val TOKENS_PER_MESSAGE_OVERHEAD = 8 // role + formatting
    const val TOKENS_PER_IMAGE = 512 // avg for 1024px vision via gemma mmproj

    fun estimateText(text: String): Int {
        if (text.isEmpty()) return 0
        return (text.length / CHARS_PER_TOKEN).coerceAtLeast(1)
    }

    fun estimateMessage(role: String, content: String, imageCount: Int = 0): Int {
        return estimateText(content) + TOKENS_PER_MESSAGE_OVERHEAD + (imageCount * TOKENS_PER_IMAGE)
    }

    fun estimateHistory(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        historyImages: List<List<String>> = emptyList()
    ): Int {
        var total = estimateText(systemPrompt) + TOKENS_PER_MESSAGE_OVERHEAD
        for ((idx, pair) in history.withIndex()) {
            val (role, content) = pair
            val imgs = historyImages.getOrNull(idx)?.size ?: 0
            total += estimateMessage(role, content, imgs)
        }
        return total
    }

    fun estimateHistoryWithAttachments(
        systemPrompt: String,
        history: List<MessageWithAttachments>,
        query: String = "",
        queryImages: Int = 0
    ): Int {
        var total = estimateText(systemPrompt) + TOKENS_PER_MESSAGE_OVERHEAD
        for (mwa in history) {
            total += estimateMessage(mwa.message.role, mwa.message.content, mwa.attachments.size)
        }
        if (query.isNotEmpty() || queryImages > 0) {
            total += estimateMessage("user", query, queryImages)
        }
        return total
    }

    fun estimateWithAttachmentsAndQuery(
        systemPrompt: String,
        history: List<MessageWithAttachments>,
        query: String,
        queryImageCount: Int
    ): Int = estimateHistoryWithAttachments(systemPrompt, history, query, queryImageCount)
}
