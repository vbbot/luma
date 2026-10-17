package com.vandam.luma.helper

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.vandam.luma.R
import com.vandam.luma.data.CardAction
import com.vandam.luma.data.CardBlock
import com.vandam.luma.data.CardOpenTarget
import com.vandam.luma.data.PlacedCard
import com.vandam.luma.data.Prefs
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToLong

// Draws lock screen cards (data/LockscreenCards.kt) into the container under
// the clock. Every design decision for cards lives here; integrations only
// describe content.
class LockscreenCardRenderer(
    private val context: Context,
    private val prefs: Prefs,
) {
    private companion object {
        const val PRIMARY_SP = 18f
        const val SECONDARY_SP = 14f
        const val ARROW_DP = 48
        const val ARROW_PADDING_DP = 12
        const val CARD_GAP_DP = 20
        const val ROW_GAP_DP = 6
        const val PROGRESS_WIDTH_DP = 120
        const val SECONDARY_ALPHA = 0xBB
    }

    // Rebuilding the views while a finger is down cancels the press (a server
    // update arriving mid long-press), so renders wait until it lifts.
    private var touching = false
    private var deferredRender: (() -> Unit)? = null

    // What was last drawn; identical renders are skipped.
    private var lastSignature: String? = null

    private val touchTracker =
        View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> touching = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    deferredRender?.let { render ->
                        deferredRender = null
                        view.post(render)
                    }
                }
            }
            false
        }

    fun render(
        container: LinearLayout,
        placed: List<PlacedCard>,
        pageIdFor: (PlacedCard) -> String,
        now: Long,
        interactive: Boolean,
        textColor: Int,
        onStep: (cardId: String, pageId: String) -> Unit,
        onOpen: (CardOpenTarget) -> Unit,
        // Text shown instead of a page whose action is in flight, e.g. "✓ Done".
        pendingTextFor: (cardId: String, pageId: String) -> String? = { _, _ -> null },
        onAction: (cardId: String, pageId: String, action: CardAction) -> Unit = { _, _, _ -> },
    ) {
        if (touching) {
            deferredRender = {
                render(container, placed, pageIdFor, now, interactive, textColor, onStep, onOpen, pendingTextFor, onAction)
            }
            return
        }
        val signature =
            buildString {
                append(interactive).append(textColor).append(container.childCount > 0)
                placed.forEach { entry ->
                    val pageId = pageIdFor(entry)
                    append(entry).append(pageId).append(pendingTextFor(entry.card.id, pageId))
                    // Time blocks read differently as minutes pass.
                    if (entry.pages.any { page -> page.blocks.any { it is CardBlock.Time } }) append(now / 60_000)
                }
            }
        if (signature == lastSignature && container.childCount > 0 == placed.isNotEmpty()) return
        lastSignature = signature

        container.removeAllViews()
        placed.forEachIndexed { index, entry ->
            val cardView =
                cardView(entry, pageIdFor(entry), now, interactive, textColor, onStep, onOpen, pendingTextFor, onAction)
            if (index > 0) {
                cardView.layoutParams = verticalParams().apply { topMargin = dp(CARD_GAP_DP) }
            }
            container.addView(cardView)
        }
        container.visibility = if (placed.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun cardView(
        entry: PlacedCard,
        pageId: String,
        now: Long,
        interactive: Boolean,
        textColor: Int,
        onStep: (String, String) -> Unit,
        onOpen: (CardOpenTarget) -> Unit,
        pendingTextFor: (String, String) -> String?,
        onAction: (String, String, CardAction) -> Unit,
    ): LinearLayout {
        val card = entry.card
        val pages = entry.pages
        val index = pages.indexOfFirst { it.id == pageId }.coerceAtLeast(0)
        val page = pages[index]
        val paged = !entry.asFallback && pages.size > 1

        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = verticalParams()
            }
        if (paged) {
            // pages can include one whose action is still showing "✓ Done".
            val position = "${index + 1}/${maxOf(card.total ?: pages.size, pages.size)}"
            column.addView(text(card.title?.let { "$it · $position" } ?: position, secondary = true, textColor))
        }

        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = verticalParams()
            }
        val showArrows = paged && interactive
        row.addView(
            arrow(R.drawable.chevron_left_32px, R.string.content_desc_previous_page, showArrows, textColor) {
                onStep(card.id, pages[(index - 1 + pages.size) % pages.size].id)
            },
        )
        val content =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        val pendingText = if (entry.asFallback) null else pendingTextFor(card.id, page.id)
        if (entry.asFallback) {
            content.addView(text(card.fallback, secondary = false, textColor))
        } else if (pendingText != null) {
            content.addView(text(pendingText, secondary = false, textColor))
            // Same height as the page it replaces, so nothing moves under the
            // finger; the second row says how to undo.
            if (card.regular) {
                val hint = if (page.primary?.undo == true) context.getString(R.string.lockscreen_card_undo_hint) else " "
                content.addView(
                    text(hint, secondary = true, textColor).apply {
                        layoutParams = verticalParams().apply { topMargin = dp(ROW_GAP_DP) }
                    },
                )
            }
        } else {
            page.blocks.take(if (card.regular) 2 else 1).forEachIndexed { blockIndex, block ->
                val view = blockView(block, now, textColor)
                if (blockIndex > 0) view.layoutParams = verticalParams().apply { topMargin = dp(ROW_GAP_DP) }
                content.addView(view)
            }
        }
        val target = page.open.takeIf { !entry.asFallback }
        if (target != null && interactive) {
            content.isClickable = true
            content.setOnClickListener { onOpen(target) }
        }
        // Long-press runs the action; on a pending page it is the undo, which
        // the service decides.
        val action = page.primary.takeIf { !entry.asFallback }
        if (action != null && interactive) {
            content.isLongClickable = true
            content.setOnLongClickListener {
                onAction(card.id, page.id, action)
                true
            }
        }
        content.setOnTouchListener(touchTracker)
        row.addView(content)
        row.addView(
            arrow(R.drawable.chevron_right_32px, R.string.content_desc_next_page, showArrows, textColor) {
                onStep(card.id, pages[(index + 1) % pages.size].id)
            },
        )
        column.addView(row)
        return column
    }

    private fun blockView(
        block: CardBlock,
        now: Long,
        textColor: Int,
    ): View =
        when (block) {
            is CardBlock.Text -> text(block.text, block.secondary, textColor)
            is CardBlock.Time -> text(formatTime(block, now), secondary = false, textColor)
            is CardBlock.Pair ->
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = verticalParams()
                    addView(
                        text(block.left, secondary = false, textColor).apply {
                            gravity = Gravity.START
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        },
                    )
                    if (block.right.isNotEmpty()) {
                        addView(
                            text(block.right, secondary = false, textColor).apply {
                                layoutParams =
                                    LinearLayout
                                        .LayoutParams(
                                            LinearLayout.LayoutParams.WRAP_CONTENT,
                                            LinearLayout.LayoutParams.WRAP_CONTENT,
                                        ).apply { marginStart = dp(12) }
                            },
                        )
                    }
                }
            is CardBlock.Progress ->
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = verticalParams()
                    val count = "${block.value.toInt()}/${block.max.toInt()}"
                    addView(text(block.label?.let { "$it $count" } ?: count, secondary = true, textColor))
                    addView(progressBar(block.value / block.max, textColor))
                }
        }

    private fun progressBar(
        fraction: Double,
        textColor: Int,
    ): FrameLayout {
        val width = dp(PROGRESS_WIDTH_DP)
        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(width, dp(2)).apply { topMargin = dp(4) }
            addView(
                View(context).apply {
                    setBackgroundColor(withAlpha(textColor, 0x55))
                    layoutParams = FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.MATCH_PARENT)
                },
            )
            addView(
                View(context).apply {
                    setBackgroundColor(textColor)
                    layoutParams =
                        FrameLayout.LayoutParams((width * fraction.coerceIn(0.0, 1.0)).toInt(), FrameLayout.LayoutParams.MATCH_PARENT)
                },
            )
        }
    }

    private fun text(
        value: String,
        secondary: Boolean,
        textColor: Int,
    ) = TextView(context).apply {
        text = value
        typeface = ResourcesCompat.getFont(context, R.font.publicsans_regular)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (secondary) SECONDARY_SP else PRIMARY_SP)
        setTextColor(if (secondary) withAlpha(textColor, SECONDARY_ALPHA) else textColor)
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        layoutParams = verticalParams()
    }

    private fun arrow(
        icon: Int,
        description: Int,
        visible: Boolean,
        textColor: Int,
        onClick: () -> Unit,
    ) = ImageView(context).apply {
        setImageResource(icon)
        setColorFilter(textColor)
        contentDescription = context.getString(description)
        val padding = dp(ARROW_PADDING_DP)
        setPadding(padding, padding, padding, padding)
        layoutParams = LinearLayout.LayoutParams(dp(ARROW_DP), dp(ARROW_DP))
        // Invisible rather than gone, so the text stays centred either way.
        visibility = if (visible) View.VISIBLE else View.INVISIBLE
        setOnTouchListener(touchTracker)
        if (visible) {
            setOnClickListener {
                performHapticFeedback(context)
                onClick()
            }
        }
    }

    // clock: "14:30" in the phone's time format; relative: "in 5 min",
    // "5 min ago"; countdown: "5 min", "Now".
    private fun formatTime(
        block: CardBlock.Time,
        now: Long,
    ): String {
        val minutes = ((block.at - now) / 60_000.0).roundToLong()
        val text =
            when (block.format) {
                CardBlock.Time.Format.Clock ->
                    formatClockText(prefs, Calendar.getInstance().apply { timeInMillis = block.at })
                CardBlock.Time.Format.Countdown -> if (minutes <= 0) "Now" else duration(minutes)
                CardBlock.Time.Format.Relative ->
                    when {
                        minutes == 0L -> "now"
                        minutes > 0 -> "in ${duration(minutes)}"
                        else -> "${duration(abs(minutes))} ago"
                    }
            }
        return block.prefix?.let { "$it $text" } ?: text
    }

    private fun duration(minutes: Long) =
        if (minutes < 60) "$minutes min" else if (minutes % 60 == 0L) "${minutes / 60} h" else "${minutes / 60} h ${minutes % 60} min"

    private fun withAlpha(
        color: Int,
        alpha: Int,
    ) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun verticalParams() =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()
}
