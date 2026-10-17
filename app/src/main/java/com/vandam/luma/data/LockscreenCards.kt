package com.vandam.luma.data

import android.util.Log
import dev.convex.android.ConvexClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

// Cards from dashboard integrations, shown under the lock screen clock. The
// format is specified in the dashboard repo: docs/lockscreen-cards.md.
// Parsing is lenient by design: unknown blocks are skipped and a card that
// cannot be drawn falls back to its one-line `fallback`.

sealed interface CardBlock {
    data class Text(
        val text: String,
        val secondary: Boolean,
    ) : CardBlock

    data class Pair(
        val left: String,
        val right: String,
    ) : CardBlock

    data class Progress(
        val value: Double,
        val max: Double,
        val label: String?,
    ) : CardBlock

    data class Time(
        val at: Long,
        val format: Format,
        val prefix: String?,
    ) : CardBlock {
        enum class Format { Clock, Relative, Countdown }
    }
}

sealed interface CardOpenTarget {
    data class ToolTarget(
        val toolId: String,
    ) : CardOpenTarget

    data class AppTarget(
        val packageName: String,
    ) : CardOpenTarget
}

data class CardPage(
    val id: String,
    val validFrom: Long?,
    val validUntil: Long?,
    val blocks: List<CardBlock>,
    val open: CardOpenTarget?,
) {
    fun isValidAt(now: Long) = (validFrom == null || validFrom <= now) && (validUntil == null || validUntil > now)
}

data class LockscreenCard(
    val id: String,
    val expiresAt: Long,
    val fallback: String,
    val title: String?,
    val regular: Boolean,
    val highRelevanceUntil: Long?,
    val startPageId: String?,
    val resetOnWake: Boolean,
    val total: Int?,
    val pages: List<CardPage>,
) {
    fun validPages(now: Long) = pages.filter { it.isValidAt(now) }

    fun isHighRelevance(now: Long) = highRelevanceUntil != null && highRelevanceUntil > now
}

// A card placed on the lock screen: everything, or its fallback when it no
// longer fits.
data class PlacedCard(
    val card: LockscreenCard,
    val pages: List<CardPage>,
    val asFallback: Boolean,
)

object LockscreenCards {
    const val PROTOCOL = 1
    val BLOCK_TYPES = listOf("text", "pair", "progress", "time")

    // Content rows under the clock. regular cards take 2, compact 1; a paged
    // card's header comes on top of that.
    private const val ROW_BUDGET = 4
    private const val MAX_CARDS = 2
    private const val LOG_TAG = "LockscreenCards"
    private const val QUERY_PATH = "lockscreen:getForDevice"

    fun observe(
        client: ConvexClient,
        accountNumber: String,
    ): Flow<List<LockscreenCard>> {
        if (!accountNumber.matches(Regex("^\\d{16}$"))) return flowOf(emptyList())

        return runCatching {
            client
                .subscribe<List<JsonObject>>(
                    name = QUERY_PATH,
                    args =
                        mapOf(
                            "accountNumber" to accountNumber,
                            "protocol" to PROTOCOL.toDouble(),
                            "blocks" to BLOCK_TYPES,
                        ),
                ).transform { result ->
                    result.fold(
                        onSuccess = { cards -> emit(cards.mapNotNull(::parseCard)) },
                        // Servers without lock screen integrations do not have this query.
                        onFailure = { error -> Log.w(LOG_TAG, "Card subscription failed", error) },
                    )
                }
        }.getOrElse { error ->
            Log.w(LOG_TAG, "Unable to observe cards", error)
            emptyFlow()
        }
    }

    // Which cards to draw now, in order: high relevance first, then the
    // dashboard's order, within the row budget.
    fun place(
        cards: List<LockscreenCard>,
        now: Long,
    ): List<PlacedCard> {
        val candidates =
            cards
                .filter { it.expiresAt > now && it.validPages(now).isNotEmpty() }
                .sortedByDescending { it.isHighRelevance(now) }
        var rowsLeft = ROW_BUDGET
        val placed = mutableListOf<PlacedCard>()
        for (card in candidates) {
            if (placed.size == MAX_CARDS || rowsLeft == 0) break
            val rows = if (card.regular) 2 else 1
            val pages = card.validPages(now)
            if (rows <= rowsLeft) {
                placed += PlacedCard(card, pages, asFallback = false)
                rowsLeft -= rows
            } else {
                placed += PlacedCard(card, pages, asFallback = true)
                rowsLeft -= 1
            }
        }
        return placed
    }

    // The next time something on screen changes by itself: a card expiring or
    // dropping relevance, a page window opening or closing, or a time block's
    // minute ticking over.
    fun nextChangeAt(
        cards: List<LockscreenCard>,
        now: Long,
    ): Long? {
        val times = mutableListOf<Long>()
        for (card in cards) {
            times += card.expiresAt
            card.highRelevanceUntil?.let(times::add)
            for (page in card.pages) {
                page.validFrom?.let(times::add)
                page.validUntil?.let(times::add)
            }
        }
        if (place(cards, now).any { placed -> placed.pages.any { page -> page.blocks.any { it is CardBlock.Time } } }) {
            times += (now / 60_000 + 1) * 60_000
        }
        return times.filter { it > now }.minOrNull()
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun parseCard(json: JsonObject): LockscreenCard? =
        runCatching {
            val schema = json.number("schema")?.toInt() ?: return null
            if (schema > PROTOCOL) return null
            val pages = (json["pages"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parsePage) }
            val fallback = json.string("fallback")?.takeIf { it.isNotBlank() } ?: return null
            val paging = json.obj("paging")
            LockscreenCard(
                id = json.string("id") ?: return null,
                expiresAt = json.number("expiresAt")?.toLong() ?: return null,
                fallback = fallback,
                title = json.string("title"),
                regular = json.string("size") == "regular",
                highRelevanceUntil =
                    if (json.string("relevance") == "high") json.number("relevantUntil")?.toLong() ?: Long.MAX_VALUE else null,
                startPageId = paging?.string("start"),
                resetOnWake = paging?.get("resetOnWake")?.jsonPrimitive?.booleanOrNull ?: true,
                total = paging?.number("total")?.toInt(),
                // A card with nothing drawable still shows its fallback.
                pages = pages.ifEmpty { listOf(textPage(fallback)) },
            )
        }.getOrElse { error ->
            Log.w(LOG_TAG, "Skipping a card that could not be read", error)
            null
        }

    private fun textPage(text: String) =
        CardPage(id = "fallback", validFrom = null, validUntil = null, blocks = listOf(CardBlock.Text(text, false)), open = null)

    private fun parsePage(json: JsonObject): CardPage? {
        val blocks = (json["blocks"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseBlock) }
        if (blocks.isEmpty()) return null
        val open =
            json.obj("open")?.let { target ->
                target.string("tool")?.let(CardOpenTarget::ToolTarget)
                    ?: target.string("app")?.let(CardOpenTarget::AppTarget)
            }
        return CardPage(
            id = json.string("id") ?: return null,
            validFrom = json.number("validFrom")?.toLong(),
            validUntil = json.number("validUntil")?.toLong(),
            blocks = blocks,
            open = open,
        )
    }

    // Unknown block types return null and are skipped.
    private fun parseBlock(json: JsonObject): CardBlock? =
        when (json.string("type")) {
            "text" -> json.string("text")?.let { CardBlock.Text(it, secondary = json.string("role") == "secondary") }
            "pair" -> json.string("left")?.let { CardBlock.Pair(it, json.string("right").orEmpty()) }
            "progress" -> {
                val value = json.number("value")
                val max = json.number("max")
                if (value != null && max != null && max > 0) CardBlock.Progress(value, max, json.string("label")) else null
            }
            "time" -> {
                val at = json.number("at")?.toLong()
                val format =
                    when (json.string("format")) {
                        "clock" -> CardBlock.Time.Format.Clock
                        "relative" -> CardBlock.Time.Format.Relative
                        "countdown" -> CardBlock.Time.Format.Countdown
                        else -> null
                    }
                if (at != null && format != null) CardBlock.Time(at, format, json.string("prefix")) else null
            }
            else -> null
        }
}
